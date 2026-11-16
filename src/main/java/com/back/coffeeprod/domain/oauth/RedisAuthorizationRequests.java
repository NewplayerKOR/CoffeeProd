package com.back.coffeeprod.domain.oauth;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Component
public class RedisAuthorizationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private final AuthStore store;private final OAuthFlow flow;
    private final ObjectMapper mapper=new ObjectMapper();
    public RedisAuthorizationRequests(AuthStore store,OAuthFlow flow){this.store=store;this.flow=flow;}
    public record Saved(String authorizationUri,String clientId,String redirectUri,Set<String> scopes,String state,Map<String,Object> parameters,Map<String,Object> attributes) { }
    private OAuth2AuthorizationRequest decode(String json) {
        if(json==null)return null;
        var r=mapper.readValue(json,Saved.class);
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri(r.authorizationUri()).clientId(r.clientId()).redirectUri(r.redirectUri()).scopes(r.scopes()).state(r.state()).additionalParameters(r.parameters()).attributes(r.attributes()).build();
    }
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return decode(store.read(flow.transaction(request)).get("authorizationRequest"));
    }
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest r,HttpServletRequest request,HttpServletResponse response) {
        if(r==null)return;
        String id=flow.transaction(request);
        if(!store.transition(id,"NEW","AUTHORIZING"))throw new IllegalArgumentException("인증 거래 재사용");
        store.put(id,"authorizationRequest",mapper.writeValueAsString(new Saved(r.getAuthorizationUri(),r.getClientId(),r.getRedirectUri(),r.getScopes(),r.getState(),r.getAdditionalParameters(),r.getAttributes())));
    }
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,HttpServletResponse response) {
        String id;
        try {id=flow.transaction(request);}catch(com.back.coffeeprod.global.exception.CustomException e){return null;}
        // state를 비교한 후에만 원자적으로 소비하여 잘못된 콜백의 정상 거래 폐기를 막는다.
        var saved=decode(store.read(id).get("authorizationRequest"));
        if(saved==null || !Objects.equals(saved.getState(),request.getParameter("state")))return null;
        if(!store.transition(id,"AUTHORIZING","CALLBACK"))return null;
        return decode(store.takeField(id,"authorizationRequest"));
    }
}

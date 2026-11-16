package com.back.coffeeprod.domain.oauth;

import com.back.coffeeprod.global.exception.*;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.IOException;
import java.util.*;

@Component
public class OAuthFlow {
    public static final String COOKIE="coffeeprod_oauth";
    private final AuthStore store;
    private final OAuthSettings settings;
    public OAuthFlow(AuthStore store,OAuthSettings settings){this.store=store;this.settings=settings;}
    public String transaction(HttpServletRequest request) {
        if(request.getCookies()!=null) for(var cookie:request.getCookies())if(COOKIE.equals(cookie.getName()) && cookie.getValue().matches("[A-Za-z0-9_-]{43}"))return cookie.getValue();
        throw new CustomException(ErrorCode.OAUTH_TRANSACTION_INVALID);
    }
    public Map<String,String> ready(HttpServletRequest request) {
        var data=store.read(transaction(request));
        if(!"READY".equals(data.get("status")))throw new CustomException(ErrorCode.OAUTH_TRANSACTION_INVALID);
        return data;
    }
    public void checkOrigin(HttpServletRequest request) {
        if(!settings.getFrontendUrl().equals(request.getHeader("Origin")) || !"1".equals(request.getHeader("X-OAuth-Request")))
            throw new CustomException(ErrorCode.ACCESS_DENIED);
    }
    public String begin(HttpServletResponse response,String provider,String purpose,Long memberId) {
        String id=AuthStore.random();
        store.create(id,Map.of("status","NEW","provider",provider,"purpose",purpose,"memberId",memberId==null?"":memberId.toString(),"confirmationExpires",""+(System.currentTimeMillis()+300000)),600);
        response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,id).httpOnly(true).secure(settings.secure()).sameSite("Lax").path("/").maxAge(600).build().toString());
        return settings.getBackendUrl()+"/oauth2/authorization/"+provider;
    }
    public void success(HttpServletRequest request,HttpServletResponse response,org.springframework.security.core.Authentication authentication) throws IOException {
        try {
            String id=transaction(request); var data=store.read(id);
            if(!"CALLBACK".equals(data.get("status")))throw new IllegalArgumentException();
            ProviderIdentity identity=ProviderIdentity.from((OAuth2AuthenticationToken)authentication);
            if(!identity.provider().equals(data.get("provider")))throw new IllegalArgumentException();
            store.put(id,"subjectHash",identity.subjectHash());store.put(id,"email",identity.email());store.put(id,"verified",""+identity.verified());
            if(!store.transition(id,"CALLBACK","READY"))throw new IllegalArgumentException();
        } catch(RuntimeException e) { failure(request,response,null); return; }
        SecurityContextHolder.clearContext();
        response.sendRedirect(settings.getFrontendUrl()+"/auth/callback");
    }
    public void failure(HttpServletRequest request,HttpServletResponse response,Exception exception) throws IOException {
        try {store.put(transaction(request),"status","ERROR");}catch(RuntimeException ignored) { }
        SecurityContextHolder.clearContext();
        response.sendRedirect(settings.getFrontendUrl()+"/auth/callback");
    }
}

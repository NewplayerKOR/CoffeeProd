package com.back.coffeeprod.domain.oauth;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.web.*;
import org.springframework.security.web.context.NullSecurityContextRepository;
import jakarta.servlet.http.*;
import java.util.*;

@Configuration
public class OAuthSecurityConfiguration {
    @Bean
    ClientRegistrationRepository oauthRegistrations(OAuthSettings s) {
        Map<String,ClientRegistration> registrations=new HashMap<>();
        if(s.enabled("google"))registrations.put("google",CommonOAuth2Provider.GOOGLE.getBuilder("google")
            .clientId(s.getGoogleId()).clientSecret(s.getGoogleSecret()).scope("openid","email","profile")
            .redirectUri(s.getBackendUrl()+"/login/oauth2/code/google").build());
        if(s.enabled("kakao"))registrations.put("kakao",ClientRegistration.withRegistrationId("kakao")
            .clientId(s.getKakaoId()).clientSecret(s.getKakaoSecret()).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(s.getBackendUrl()+"/login/oauth2/code/kakao").scope(s.isKakaoEmailScope()?new String[]{"profile_nickname","account_email"}:new String[]{"profile_nickname"})
            .authorizationUri("https://kauth.kakao.com/oauth/authorize").tokenUri("https://kauth.kakao.com/oauth/token")
            .userInfoUri("https://kapi.kakao.com/v2/user/me").userNameAttributeName("id").clientName("Kakao").build());
        return registrations::get;
    }
    @Bean @Order(1)
    SecurityFilterChain oauthFilterChain(HttpSecurity http,ClientRegistrationRepository registrations,RedisAuthorizationRequests requests,OAuthFlow flow,AuthStore store) throws Exception {
        var delegate=new DefaultOAuth2AuthorizationRequestResolver(registrations,"/oauth2/authorization");
        OAuth2AuthorizationRequestResolver resolver=new OAuth2AuthorizationRequestResolver() {
            private OAuth2AuthorizationRequest resolveRequest(HttpServletRequest request,String id) {
                var result=id==null?delegate.resolve(request):delegate.resolve(request,id);
                if(result==null)return null;
                var tx=store.read(flow.transaction(request));
                if(!"NEW".equals(tx.get("status")))throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(new OAuth2Error("invalid_request"));
                if(!request.getRequestURI().endsWith("/"+tx.get("provider")))throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(new OAuth2Error("invalid_request"));
                if(!"LOGIN".equals(tx.get("purpose")))return OAuth2AuthorizationRequest.from(result).additionalParameters(p->{p.put("prompt","login");if("google".equals(tx.get("provider")))p.put("max_age","0");}).build();
                return result;
            }
            public OAuth2AuthorizationRequest resolve(HttpServletRequest r){return resolveRequest(r,null);}
            public OAuth2AuthorizationRequest resolve(HttpServletRequest r,String id){return resolveRequest(r,id);}
        };
        // 제공사 토큰과 OAuth principal은 일반 로그인 세션으로 저장하지 않는다.
        OAuth2AuthorizedClientRepository clients=new OAuth2AuthorizedClientRepository() {
            public <T extends OAuth2AuthorizedClient>T loadAuthorizedClient(String id,org.springframework.security.core.Authentication a,HttpServletRequest r){return null;}
            public void saveAuthorizedClient(OAuth2AuthorizedClient c,org.springframework.security.core.Authentication a,HttpServletRequest r,HttpServletResponse s){}
            public void removeAuthorizedClient(String id,org.springframework.security.core.Authentication a,HttpServletRequest r,HttpServletResponse s){}
        };
        http.securityMatcher("/oauth2/authorization/**","/login/oauth2/code/**")
            .csrf(c->c.disable()).requestCache(c->c.disable())
            .securityContext(c->c.securityContextRepository(new NullSecurityContextRepository()))
            .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c->c.anyRequest().permitAll())
            .oauth2Login(c->c.clientRegistrationRepository(registrations).authorizedClientRepository(clients)
                .authorizationEndpoint(a->a.authorizationRequestResolver(resolver).authorizationRequestRepository(requests))
                .successHandler(flow::success).failureHandler(flow::failure));
        return http.build();
    }
}

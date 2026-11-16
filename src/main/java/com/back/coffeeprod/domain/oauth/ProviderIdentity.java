package com.back.coffeeprod.domain.oauth;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import java.util.Map;

public record ProviderIdentity(String provider,String subjectHash,String email,boolean verified) {
    public static ProviderIdentity from(OAuth2AuthenticationToken auth) {
        String provider=auth.getAuthorizedClientRegistrationId();
            String subject; String email; boolean verified;
        if (provider.equals("google") && auth.getPrincipal() instanceof OidcUser user) {
            subject=user.getSubject(); email=user.getEmail(); verified=Boolean.TRUE.equals(user.getEmailVerified());
        } else if(provider.equals("kakao")) {
            Object id=auth.getPrincipal().getAttribute("id");
            if(id==null) throw new IllegalArgumentException("제공사 식별자 없음");
            subject=id.toString();
            Map<String,Object> account=auth.getPrincipal().getAttribute("kakao_account");
            email=account==null?null:(String)account.get("email");
            verified=account!=null && Boolean.TRUE.equals(account.get("is_email_valid")) && Boolean.TRUE.equals(account.get("is_email_verified"));
        } else throw new IllegalArgumentException("지원하지 않는 제공사");
        if(subject==null || subject.isBlank()) throw new IllegalArgumentException("제공사 식별자 없음");
        return new ProviderIdentity(provider,AuthStore.hash(provider+":"+subject),email==null?"":email,verified);
    }
}

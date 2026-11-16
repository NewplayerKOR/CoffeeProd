package com.back.coffeeprod.domain.oauth;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;

@Component
@Getter
public class OAuthSettings {
    @Value("${APP_BACKEND_URL:http://localhost:8080}") private String backendUrl;
    @Value("${APP_FRONTEND_URL:http://localhost:3000}") private String frontendUrl;
    @Value("${OAUTH_GOOGLE_CLIENT_ID:}") private String googleId;
    @Value("${OAUTH_GOOGLE_CLIENT_SECRET:}") private String googleSecret;
    @Value("${OAUTH_KAKAO_CLIENT_ID:}") private String kakaoId;
    @Value("${OAUTH_KAKAO_CLIENT_SECRET:}") private String kakaoSecret;
    @Value("${OAUTH_KAKAO_EMAIL_SCOPE:false}") private boolean kakaoEmailScope;
    @Value("${AUTH_VERIFICATION_SECRET:}") private String verificationSecret;
    @Value("${RESEND_API_KEY:}") private String resendKey;
    @Value("${MAIL_PROVIDER:resend}") private String mailProvider;
    @Value("${MAIL_FROM:CoffeeProd <no-reply@mailsender.ttagyulab.com>}") private String mailFrom;
    public boolean secure() { return URI.create(backendUrl).getScheme().equals("https"); }
    public boolean enabled(String provider) {
        return switch (provider) {
            case "google" -> !googleId.isBlank() && !googleSecret.isBlank();
            case "kakao" -> !kakaoId.isBlank() && !kakaoSecret.isBlank();
            default -> false;
        };
    }
}

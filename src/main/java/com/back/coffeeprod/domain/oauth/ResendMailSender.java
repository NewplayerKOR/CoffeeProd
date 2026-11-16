package com.back.coffeeprod.domain.oauth;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.util.Map;
import java.util.List;

@Component
public class ResendMailSender implements MailSender {
    private final OAuthSettings settings;
    private final RestClient client;
    public ResendMailSender(OAuthSettings settings) {
        this.settings=settings;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        client=RestClient.builder().baseUrl("https://api.resend.com").requestFactory(factory).build();
    }
    public Outcome send(String email,String code,String key) {
        if(settings.getResendKey().isBlank() || !"resend".equals(settings.getMailProvider())) return Outcome.REJECTED;
        try {
            client.post().uri("/emails").header("Authorization","Bearer "+settings.getResendKey())
                .header("Idempotency-Key",key).body(Map.of("from",settings.getMailFrom(),"to",List.of(email),
                "subject","CoffeeProd 이메일 인증번호","text","인증번호: "+code+"\n5분 안에 입력해 주세요."))
                .retrieve().toBodilessEntity();
            return Outcome.SENT;
        } catch(RestClientResponseException e) {
            return e.getStatusCode().is4xxClientError() && e.getStatusCode().value()!=408 ? Outcome.REJECTED : Outcome.UNKNOWN;
        } catch (org.springframework.web.client.RestClientException e) { return Outcome.UNKNOWN; }
    }
}

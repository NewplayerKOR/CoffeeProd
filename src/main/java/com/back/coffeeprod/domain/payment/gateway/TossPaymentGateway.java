package com.back.coffeeprod.domain.payment.gateway;
import com.back.coffeeprod.domain.payment.dto.TossPaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component @Profile({"prod", "local-toss"})
public class TossPaymentGateway implements PaymentGateway {
    private final RestClient client;
    // Unknown 4xx codes never release reserved resources.
    private static final Set<String> FINAL_CONFIRM_ERRORS = Set.of("REJECT_CARD_COMPANY",
            "EXCEED_MAX_DAILY_PAYMENT_COUNT", "EXCEED_MAX_PAYMENT_AMOUNT",
            "NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT", "RESTRICTED_TRANSFER_ACCOUNT");
    private static final Set<String> FINAL_CANCEL_ERRORS = Set.of("REFUND_REJECTED", "NOT_CANCELABLE_PAYMENT",
            "EXCEED_MAX_REFUND_DUE", "NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER");
    public TossPaymentGateway(@Value("${pg.toss.secret-key}") String secret,
            @Value("${pg.toss.base-url:https://api.tosspayments.com}") String baseUrl, PaymentRecoveryProperties props) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.responseTimeout());
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                        .encodeToString((secret + ":").getBytes(StandardCharsets.UTF_8)))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }
    @Override public GatewayPayment confirm(String key, String order, int amount, String idempotency) {
        return post("/v1/payments/confirm", Map.of("paymentKey",key,"orderId",order,"amount",amount), idempotency, true);
    }
    @Override public GatewayPayment lookupByPaymentKey(String key) { return get("/v1/payments/{key}", key); }
    @Override public GatewayPayment lookupByOrderId(String order) { return get("/v1/payments/orders/{id}", order); }
    @Override public GatewayPayment cancelFull(String key, String reason, String idempotency) {
        return post("/v1/payments/{key}/cancel", Map.of("cancelReason",reason), idempotency, false, key);
    }
    private GatewayPayment get(String path, String value) {
        try {
            return client.get().uri(path, value).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw new GatewayException(false);
                var body = response.bodyTo(TossPaymentResponse.class);
                return body == null ? null : body.normalized();
            });
        } catch (GatewayException e) { throw e; }
        catch (Exception e) { throw new GatewayException(false); }
    }
    private GatewayPayment post(String path, Map<String, Object> body, String idempotency, boolean confirm, Object... uriValues) {
        try {
            return client.post().uri(path, uriValues).header("Idempotency-Key", idempotency).body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            var error = response.bodyTo(ProviderError.class);
                            boolean rejected = response.getStatusCode().is4xxClientError() && error != null
                                    && (confirm ? FINAL_CONFIRM_ERRORS : FINAL_CANCEL_ERRORS).contains(error.code());
                            throw new GatewayException(rejected);
                        }
                        var result = response.bodyTo(TossPaymentResponse.class);
                        return result == null ? null : result.normalized();
                    });
        } catch (GatewayException e) { throw e; }
        catch (Exception e) { throw new GatewayException(false); }
    }
    private record ProviderError(String code) {}
}

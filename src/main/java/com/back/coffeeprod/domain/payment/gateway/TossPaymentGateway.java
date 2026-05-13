package com.back.coffeeprod.domain.payment.gateway;

import com.back.coffeeprod.domain.payment.dto.TossPaymentResponse;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
@Profile("prod")
public class TossPaymentGateway implements PaymentGateway {

    private static final String TOSS_CONFIRM_URL =
            "https://api.tosspayments.com/v1/payments/confirm";

    private final RestClient restClient;
    private final String encodedSecretKey;

    public TossPaymentGateway(@Value("${pg.toss.secret-key}") String secretKey) {
        this.restClient = RestClient.create();

        // 토스 인증 방식: Basic Auth
        // secretKey 뒤에 ":"를 붙인 후 Base64 인코딩
        this.encodedSecretKey = Base64.getEncoder().encodeToString((secretKey + ":").getBytes());
    }

    @Override
    public TossPaymentResponse confirm(String paymentKey, Long orderId, int amount) {
        try {
            log.info("[TossPaymentGateway] 결제 승인 요청 - orderId: {}, amount: {}", orderId, amount);

            TossPaymentResponse response = restClient.post()
                    .uri(TOSS_CONFIRM_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + encodedSecretKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "paymentKey", paymentKey,
                            "orderId", String.valueOf(orderId),
                            "amount", amount
                    ))
                    .retrieve()
                    .body(TossPaymentResponse.class);

            log.info("[TossPaymentGateway] 결제 승인 성공 - paymentKey: {}", paymentKey);
            return response;
        } catch (Exception e) {
            log.error("[TosspaymentGateway] 결제 승인 실패 - orderId: {}, error: {}", orderId, e.getMessage());
            throw new CustomException(ErrorCode.PAYMENT_FAILED);
        }
    }
}

package com.back.coffeeprod.domain.payment.gateway;

import com.back.coffeeprod.domain.payment.dto.TossPaymentResponse;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
@Profile({"prod", "local-toss"})
public class TossPaymentGateway implements PaymentGateway {

    private static final String TOSS_CONFIRM_URL =
            "https://api.tosspayments.com/v1/payments/confirm";

    private final RestClient restClient;

    public TossPaymentGateway(@Value("${pg.toss.secret-key}") String secretKey) {

        // Secret Key를 Base64 인코딩 후 Basic 인증 후 헤더로 전달
        String encodedKey = Base64.getEncoder().encodeToString((secretKey + ":").getBytes());

        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public TossPaymentResponse confirm(String paymentKey, String tossOrderId, int amount) {
        log.info("[TossPaymentGateway] 결제 승인 요청 - orderId: {}, amount: {}", tossOrderId, amount);

        // 토스 API 요청 바디
        Map<String, Object> requestBody = Map.of(
                "paymentKey", paymentKey,
                "orderId", tossOrderId,
                "amount", amount
        );

        try {
            TossPaymentResponse response = restClient.post()
                    .uri(TOSS_CONFIRM_URL)
                    // 같은 주문 승인 요청이 재시도될 때 중복 처리 위험 줄임
                    .header("Idempotency-Key", tossOrderId)
                    .body(requestBody)
                    .retrieve()
                    // 4xx, 5xx 에러 시 결제 실패로 처리
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        log.error("[TossPaymentGateway] 결제 승인 실패 - status: {}",
                                res.getStatusCode());
                        throw new CustomException(ErrorCode.PAYMENT_FAILED);
                    })
                    .body(TossPaymentResponse.class);

            log.info("[TossPaymentGateway] 결제 승인 완료 - paymentKey: {}", paymentKey);
            return response;

        } catch (CustomException e) {
            throw e; // CustomException은 그대로 전파
        } catch (Exception e) {
            log.error("[TossPaymentGateway] 결제 승인 중 예외 발생", e);
            throw new CustomException(ErrorCode.PAYMENT_FAILED);
        }
    }
}
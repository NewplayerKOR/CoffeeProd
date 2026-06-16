package com.back.coffeeprod.domain.payment.gateway;

import com.back.coffeeprod.domain.payment.dto.TossPaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile({"dev", "test"}) // dev, test 프로파일에서 활성화
public class FakePaymentGateway implements PaymentGateway {

    @Override
    public TossPaymentResponse confirm(String paymentKey, String tossOrderId, int amount) {
        log.info("[FakePaymentGateway] 가짜 결제 승인 - orderId: {}, amount: {}",
                tossOrderId, amount);

        // 항상 성공 응답 반환
        TossPaymentResponse response = new TossPaymentResponse();
        setFields(response, paymentKey, tossOrderId, amount);

        log.info("[FakePaymentGateway] 결제 승인 완료 - paymentKey: {}", paymentKey);
        return response;
    }

    // 실제 운영 x 단순 구현
    private void setFields(TossPaymentResponse response,
                           String paymentKey, String tossOrderId, int amount) {
        try {
            setField(response, "paymentKey", paymentKey);
            setField(response, "orderId", tossOrderId);
            setField(response, "status", "DONE");
            setField(response, "totalAmount", amount);
            setField(response, "method", "카드");
            setField(response, "approvedAt",
                    java.time.LocalDateTime.now().toString());
        } catch (Exception e) {
            log.error("[FakePaymentGateway] 필드 세팅 실패", e);
        }
    }

    private void setField(Object obj, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        var field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
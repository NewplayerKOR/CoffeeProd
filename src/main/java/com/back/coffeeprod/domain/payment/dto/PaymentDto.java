package com.back.coffeeprod.domain.payment.dto;

import com.back.coffeeprod.domain.payment.entity.Payment;
import com.back.coffeeprod.domain.payment.entity.PaymentStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class PaymentDto {

    // 결제 승인 요청 DTO
    // 토스에서 클라이언트로 전달된 3가지 값을 서버로 전송
    @Getter
    @NoArgsConstructor
    public static class ConfirmRequest {
        private String paymentKey; // 토스 발급 결제 고유 키
        private Long orderId;      // 우리 서버의 주문 ID
        private int amount;        // 결제 금액 (위변조 검증 대상)
    }

    // 결제 응답 DTO
    @Getter
    public static class Response {
        private final Long paymentId;
        private final Long orderId;
        private final String pgProvider;
        private final String paymentKey;
        private final String payMethod;
        private final PaymentStatus status;
        private final LocalDateTime paidAt;

        public Response(Payment payment) {
            this.paymentId = payment.getId();
            this.orderId = payment.getOrders().getId();
            this.pgProvider = payment.getPgProvider();
            this.paymentKey = payment.getPaymentKey();
            this.payMethod = payment.getPayMethod();
            this.status = payment.getStatus();
            this.paidAt = payment.getPaidAt();
        }
    }
}

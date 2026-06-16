package com.back.coffeeprod.domain.payment.dto;

import com.back.coffeeprod.domain.payment.entity.Payment;
import com.back.coffeeprod.domain.payment.entity.PaymentStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class PaymentDto {

    // 결제 승인 요청 DTO
    // 토스에서 클라이언트로 전달된 3가지 값을 서버로 전송
    @Getter
    @NoArgsConstructor
    public static class ConfirmRequest {

        @NotBlank(message = "paymentKey는 필수입니다.")
        private String paymentKey; // 토스 발급 결제 고유 키

        @NotBlank(message = "토스 주문번호는 필수입니다.")
        private String tossOrderId;      // 토스 결제 요청/승인에 사용한 주문 번호

        @Min(value = 0, message = "결제 금액은 0 이상이어야 합니다.")
        private int amount;        // 결제 금액 (위변조 검증 대상)
    }

    // 결제 응답 DTO
    @Getter
    public static class Response {
        private final Long paymentId;
        private final Long orderId;
        private final String tossOrderId;
        private final String pgProvider;
        private final String paymentKey;
        private final String payMethod;
        private final PaymentStatus status;
        private final LocalDateTime paidAt;

        public Response(Payment payment) {
            this.paymentId = payment.getId();
            this.orderId = payment.getOrders().getId();
            this.tossOrderId = payment.getOrders().getTossOrderId();
            this.pgProvider = payment.getPgProvider();
            this.paymentKey = payment.getPaymentKey();
            this.payMethod = payment.getPayMethod();
            this.status = payment.getStatus();
            this.paidAt = payment.getPaidAt();
        }
    }
}

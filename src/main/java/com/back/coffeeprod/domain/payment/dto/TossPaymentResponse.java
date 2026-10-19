package com.back.coffeeprod.domain.payment.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class TossPaymentResponse {

    private String paymentKey;  // 결제 고유 키
    private String orderId;     // 주문 ID
    private String status;      // 결제 상태 (DONE, CANCELED 등)
    private Integer totalAmount;    // 실제 결제된 금액; 누락은 0과 구분
    private String method;      // 결제 수단
    private String approvedAt;  // 승인 시각
    private Integer balanceAmount;
    private java.util.Map<String, Object> card;
    private java.util.Map<String, Object> easyPay;
    private java.util.List<Cancel> cancels;
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cancel(String transactionKey, int cancelAmount, String cancelStatus, String canceledAt) {}
    public com.back.coffeeprod.domain.payment.gateway.GatewayPayment normalized() {
        if (balanceAmount == null || totalAmount == null) throw new IllegalArgumentException("Missing PG amount");
        var history = cancels == null ? java.util.List.<com.back.coffeeprod.domain.payment.gateway.GatewayPayment.Cancellation>of()
                : cancels.stream().map(c -> new com.back.coffeeprod.domain.payment.gateway.GatewayPayment.Cancellation(
                        c.transactionKey(), c.cancelAmount(), c.cancelStatus(), parseTime(c.canceledAt()))).toList();
        return new com.back.coffeeprod.domain.payment.gateway.GatewayPayment(paymentKey, orderId, status, totalAmount,
                balanceAmount, method, parseTime(approvedAt), card != null, history);
    }
    private static java.time.Instant parseTime(String value) {
        return value == null ? null : java.time.OffsetDateTime.parse(value).toInstant();
    }
}

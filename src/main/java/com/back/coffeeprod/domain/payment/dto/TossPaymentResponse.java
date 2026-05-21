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
    private int totalAmount;    // 실제 결제된 금액
    private String method;      // 결제 수단
    private String approvedAt;  // 승인 시각
}

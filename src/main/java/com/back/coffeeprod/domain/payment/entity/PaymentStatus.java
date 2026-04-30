package com.back.coffeeprod.domain.payment.entity;

public enum PaymentStatus {
    SUCCESS,    // 결제 성공
    FAILED,     // 결제 실패
    REFUNDED    // 환불 완료
}

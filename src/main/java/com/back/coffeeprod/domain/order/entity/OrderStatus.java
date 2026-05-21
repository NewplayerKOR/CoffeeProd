package com.back.coffeeprod.domain.order.entity;

public enum OrderStatus {
    PENDING,    // 주문 임시 생성 (결제 전)
    PAID,       // 결제 완료
    SHIPPED,    // 배송 중
    DELIVERED,  // 배송 완료
    CANCELED    // 주문 취소
}

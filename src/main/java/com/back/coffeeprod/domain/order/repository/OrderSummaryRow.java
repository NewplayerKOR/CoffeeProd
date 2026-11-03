package com.back.coffeeprod.domain.order.repository;

import com.back.coffeeprod.domain.order.entity.OrderStatus;
import com.back.coffeeprod.domain.payment.entity.PaymentOperation;

import java.time.Instant;

/** Scalar values for order lists, without initializing order or payment collections. */
public record OrderSummaryRow(
        Long orderId,
        Long memberId,
        String memberEmail,
        OrderStatus status,
        boolean cancelRequested,
        int totalPrice,
        int usedMileage,
        Instant orderDate,
        String trackingNo,
        long itemCount,
        String firstProductName,
        PaymentOperation.Status confirmStatus,
        PaymentOperation.Status cancelStatus
) {
    public String paymentOperationStatus() {
        var operationStatus = cancelRequested && cancelStatus != null ? cancelStatus : confirmStatus;
        return operationStatus == null ? null : operationStatus.name();
    }
}

package com.back.coffeeprod.domain.payment.dto;

import com.back.coffeeprod.domain.payment.entity.PaymentOperation;
import java.time.Instant;

public record PaymentOperationDto(Long operationId, Long orderId, PaymentOperation.Type type,
        PaymentOperation.Status status, boolean cancelRequested, int attemptCount,
        Instant nextAttemptAt, String lastErrorCode, Instant updatedAt) {
    public PaymentOperationDto(PaymentOperation p) {
        this(p.getId(),p.getOrders().getId(),p.getType(),p.getStatus(),p.getOrders().isCancelRequested(),
                p.getAttemptCount(),p.getNextAttemptAt(),p.getLastErrorCode(),p.getUpdatedAt());
    }
}

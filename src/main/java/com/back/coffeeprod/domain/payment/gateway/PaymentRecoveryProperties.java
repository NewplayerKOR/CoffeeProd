package com.back.coffeeprod.domain.payment.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public record PaymentRecoveryProperties(
        @Value("${app.payment.connect-timeout:5s}") Duration connectTimeout,
        @Value("${app.payment.response-timeout:60s}") Duration responseTimeout,
        @Value("${app.payment.lease:120s}") Duration lease) {
    public PaymentRecoveryProperties {
        if (connectTimeout.isNegative() || connectTimeout.isZero() || responseTimeout.isNegative()
                || responseTimeout.isZero() || lease.compareTo(connectTimeout.plus(responseTimeout)) <= 0)
            throw new IllegalArgumentException("Payment lease must exceed connect + response timeout");
    }
}

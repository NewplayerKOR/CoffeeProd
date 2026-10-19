package com.back.coffeeprod.domain.payment.gateway;
public interface PaymentGateway {
    GatewayPayment confirm(String paymentKey, String tossOrderId, int amount, String idempotencyKey);
    GatewayPayment lookupByPaymentKey(String paymentKey);
    GatewayPayment lookupByOrderId(String tossOrderId);
    GatewayPayment cancelFull(String paymentKey, String reason, String idempotencyKey);
}

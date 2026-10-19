package com.back.coffeeprod.domain.payment.gateway;

import java.time.Instant;
import java.util.List;

/** Provider-neutral snapshot. Absence, malformed data and transport failures are UNKNOWN. */
public record GatewayPayment(String paymentKey, String orderId, String status, int totalAmount,
                             int balanceAmount, String method, Instant approvedAt,
                             boolean cardBased, List<Cancellation> cancels) {
    public record Cancellation(String transactionKey, int amount, String status, Instant canceledAt) {}
    public boolean matches(String key, String order, int amount) {
        return java.util.Objects.equals(paymentKey, key) && java.util.Objects.equals(orderId, order) && totalAmount == amount;
    }
    public boolean approved() { return "DONE".equals(status) && approvedAt != null && balanceAmount == totalAmount; }
    public Cancellation fullCancellation() {
        if (!"CANCELED".equals(status) || balanceAmount != 0 || cancels == null || cancels.size() != 1) return null;
        var c = cancels.getFirst();
        return c.amount == totalAmount && "DONE".equals(c.status) && c.canceledAt != null
                && c.transactionKey != null && !c.transactionKey.isBlank() ? c : null;
    }
}

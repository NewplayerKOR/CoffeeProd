package com.back.coffeeprod.domain.payment.gateway;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component @Profile({"dev", "test"})
public class FakePaymentGateway implements PaymentGateway {
    private final Map<String, GatewayPayment> payments = new HashMap<>();
    private final Map<String, String> keys = new HashMap<>();
    private final Map<String, GatewayPayment> results = new HashMap<>();
    protected synchronized void clearState() { payments.clear(); keys.clear(); results.clear(); }
    @Override public synchronized GatewayPayment confirm(String key, String order, int amount, String idempotency) {
        checkKey(idempotency, "CONFIRM:" + key + ":" + order + ":" + amount);
        if (results.containsKey(idempotency)) return results.get(idempotency);
        var existing = payments.get(key);
        if (existing != null) {
            if (!existing.matches(key, order, amount)) throw new GatewayException(false);
            return existing;
        }
        if (payments.values().stream().anyMatch(p -> p.orderId().equals(order))) throw new GatewayException(false);
        var payment = new GatewayPayment(key, order, "DONE", amount, amount, "카드", Instant.now(), true, List.of());
        payments.put(key, payment);
        results.put(idempotency,payment);
        return payment;
    }
    @Override public synchronized GatewayPayment lookupByPaymentKey(String key) { return payments.get(key); }
    @Override public synchronized GatewayPayment lookupByOrderId(String order) {
        return payments.values().stream().filter(p -> p.orderId().equals(order)).findFirst().orElse(null);
    }
    @Override public synchronized GatewayPayment cancelFull(String key, String reason, String idempotency) {
        checkKey(idempotency, "CANCEL:" + key + ":" + reason);
        if (results.containsKey(idempotency)) return results.get(idempotency);
        var p = payments.get(key);
        if (p == null) throw new GatewayException(false);
        if (p.fullCancellation() != null) return p;
        var canceled = new GatewayPayment(key, p.orderId(), "CANCELED", p.totalAmount(), 0, p.method(),
                p.approvedAt(), p.cardBased(), List.of(new GatewayPayment.Cancellation(UUID.randomUUID().toString(),
                p.totalAmount(), "DONE", Instant.now())));
        payments.put(key, canceled);
        results.put(idempotency,canceled);
        return canceled;
    }
    private void checkKey(String key, String request) {
        var previous = keys.putIfAbsent(key, request);
        if (previous != null && !previous.equals(request)) throw new GatewayException(false);
    }
}

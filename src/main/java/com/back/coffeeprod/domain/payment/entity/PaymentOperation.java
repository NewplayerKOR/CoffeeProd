package com.back.coffeeprod.domain.payment.entity;

import com.back.coffeeprod.domain.order.entity.Orders;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"order_id", "operation_type"}))
public class PaymentOperation {
    public enum Type { CONFIRM, CANCEL_FULL }
    public enum Status { READY, IN_FLIGHT, SUCCEEDED, REJECTED, UNKNOWN, MANUAL_REQUIRED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "order_id", nullable = false)
    private Orders orders;
    @Enumerated(EnumType.STRING) @Column(name = "operation_type", nullable = false, length = 20)
    private Type type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private Status status;
    @Column(length = 200) private String paymentKey;
    @Column(nullable = false, length = 64) private String tossOrderId;
    @Column(nullable = false) private int amount;
    @Column(length = 200) private String reason;
    @Column(nullable = false, unique = true, length = 300) private String idempotencyKey;
    @Column(nullable = false, length = 20) private String actorType;
    private Long actorId;
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ") private Instant createdAt;
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ") private Instant updatedAt;
    @Column(columnDefinition = "TIMESTAMPTZ") private Instant firstSentAt;
    @Column(nullable = false) private int attemptCount;
    @Column(columnDefinition = "TIMESTAMPTZ") private Instant nextAttemptAt;
    @Column(length = 36) private String leaseToken;
    @Column(columnDefinition = "TIMESTAMPTZ") private Instant leaseUntil;
    @Version private long version;
    @Column(length = 80) private String lastErrorCode;
    @Column(length = 64) private String pgTransactionKey;
    @Column(columnDefinition = "TIMESTAMPTZ") private Instant pgApprovedAt;
    @Column(columnDefinition = "TIMESTAMPTZ") private Instant pgCanceledAt;

    public PaymentOperation(Orders order, Type type, String key, String actor, Long actorId) {
        this.orders = order; this.type = type; this.paymentKey = key;
        this.tossOrderId = order.getTossOrderId(); this.amount = order.getTotalPrice();
        this.actorType = actor; this.actorId = actorId;
        this.reason = type == Type.CANCEL_FULL ? ("USER".equals(actor) ? "사용자 주문 전체 취소" : "관리자 주문 전체 취소") : null;
        this.idempotencyKey = type == Type.CONFIRM ? tossOrderId : UUID.randomUUID().toString();
        this.status = Status.READY; this.createdAt = Instant.now(); this.updatedAt = createdAt;
        // null means immediately eligible; do not round a wall-clock instant through the DB.
        this.nextAttemptAt = null;
        order.getPaymentOperations().add(this);
    }
    public boolean terminal() { return status == Status.SUCCEEDED || status == Status.REJECTED; }
    public boolean due(Instant now) {
        return !terminal() && status != Status.MANUAL_REQUIRED &&
                (status == Status.IN_FLIGHT ? leaseUntil != null && !leaseUntil.isAfter(now)
                        : nextAttemptAt == null || !nextAttemptAt.isAfter(now));
    }
    public boolean owns(String token, long expectedVersion) {
        return status == Status.IN_FLIGHT && java.util.Objects.equals(leaseToken, token) && version == expectedVersion;
    }
    public void claim(Instant now, Duration lease, boolean recovery) {
        if (recovery) attemptCount++;
        status = Status.IN_FLIGHT; leaseToken = UUID.randomUUID().toString();
        leaseUntil = now.plus(lease); updatedAt = now;
        if (firstSentAt == null) firstSentAt = now;
    }
    public void complete(Instant approvedAt, Instant canceledAt, String transactionKey) {
        pgApprovedAt = approvedAt; pgCanceledAt = canceledAt; pgTransactionKey = transactionKey;
        finish(Status.SUCCEEDED, null);
    }
    public void reject() { finish(Status.REJECTED, type == Type.CONFIRM ? "PAYMENT_CONFIRM_REJECTED" : "PAYMENT_CANCEL_REJECTED"); }
    public void manual(String code) { finish(Status.MANUAL_REQUIRED, code); }
    private void finish(Status value, String code) {
        status = value; lastErrorCode = code; updatedAt = Instant.now();
        leaseToken = null; leaseUntil = null; nextAttemptAt = null;
    }
    public void unknown(String code) {
        finish(Status.UNKNOWN, code);
        if (attemptCount >= 10) { manual("RECOVERY_LIMIT"); return; }
        long delay = attemptCount == 0 ? 30 : attemptCount == 1 ? 120 : attemptCount == 2 ? 600 : 3600;
        nextAttemptAt = updatedAt.plusSeconds(delay);
    }
    public void scheduleReconcile() {
        if (terminal()) return;
        if (status == Status.IN_FLIGHT && leaseUntil != null && leaseUntil.isAfter(Instant.now())) return;
        status = Status.UNKNOWN; nextAttemptAt = null; updatedAt = Instant.now();
        // A manual lookup gets one recovery pass; the retry budget is never reset.
    }
}

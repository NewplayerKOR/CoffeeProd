package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.entity.*;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.back.coffeeprod.domain.payment.entity.*;
import com.back.coffeeprod.domain.payment.gateway.*;
import com.back.coffeeprod.domain.payment.policy.MileagePolicyProperties;
import com.back.coffeeprod.domain.payment.repository.*;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.statistics.service.SalesRebuildService;
import com.back.coffeeprod.global.exception.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static com.back.coffeeprod.domain.payment.entity.PaymentOperation.Type.*;
import static com.back.coffeeprod.domain.payment.entity.PaymentOperation.Status.*;

/** All external calls live in PaymentWorkflow; every method here commits a short transaction. */
@Service @RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class PaymentTransactions {
    private final MemberRepository members;
    private final OrderRepository orders;
    private final PaymentOperationRepository operations;
    private final PaymentRepository payments;
    private final ProductRepository products;
    private final MileagePolicyProperties mileagePolicy;
    private final PaymentRecoveryProperties properties;
    private final SalesRebuildService sales;
    private final EntityManager em;

    public record Prepared(Long orderId, Long operationId, ErrorCode error) {}
    public record Claim(Long id, Long orderId, PaymentOperation.Type type, String paymentKey, String tossOrderId,
                        int amount, String reason, String idempotencyKey, String token, long version,
                        boolean lookupFirst, boolean postAllowed, boolean legacy) {}
    public record View(OrderDto.DetailResponse order, PaymentDto.Response payment, PaymentOperation.Status status,
                       ErrorCode error) {}

    public Prepared prepareConfirm(Long memberId, PaymentDto.ConfirmRequest request) {
        Long id = orders.findIdByTossOrderId(request.getTossOrderId()).orElseThrow(() -> error(ErrorCode.ORDER_NOT_FOUND));
        Orders order = lockOrder(id);
        authorize(order, memberId);
        var op = operations.findLocked(id, CONFIRM).orElse(null);
        if (op != null) {
            if (!Objects.equals(op.getPaymentKey(),request.getPaymentKey()) || op.getAmount() != request.getAmount())
                throw error(ErrorCode.PAYMENT_OPERATION_CONFLICT);
            return new Prepared(id, op.getId(), null);
        }
        if (order.getStatus() != OrderStatus.PENDING || order.isCancelRequested()) throw error(ErrorCode.INVALID_ORDER_STATUS);
        if (request.getAmount() != order.getTotalPrice()) {
            order.requestCancel(); restore(order); order.cancel();
            return new Prepared(id, null, ErrorCode.INVALID_ORDER_AMOUNT);
        }
        op = operations.save(new PaymentOperation(order, CONFIRM, request.getPaymentKey(), "USER", memberId));
        return new Prepared(id, op.getId(), null);
    }

    public Prepared prepareCancel(Long actorId, Long id, boolean admin) {
        Orders order = lockOrder(id);
        if (!admin) authorize(order, actorId);
        if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.DELIVERED)
            throw error(ErrorCode.INVALID_ORDER_STATUS);
        var confirm = operations.findLocked(id, CONFIRM).orElse(null);
        var cancel = operations.findLocked(id, CANCEL_FULL).orElse(null);
        var payment = payments.findLockedByOrderId(id).orElse(null);
        if (cancel != null) return new Prepared(id, confirm != null && !confirm.terminal() ? confirm.getId() : cancel.getId(), null);
        if (order.getStatus() == OrderStatus.CANCELED) {
            if (payment == null && (confirm == null || confirm.getStatus() == REJECTED)
                    || payment != null && payment.getStatus() == PaymentStatus.REFUNDED)
                return new Prepared(id, null, null);
            return legacy(order, payment, actorId, "LEGACY_CANCELED_PAYMENT");
        }
        order.requestCancel();
        if (order.getStatus() == OrderStatus.PENDING) {
            if (confirm != null && !confirm.terminal()) {
                operations.save(new PaymentOperation(order,CANCEL_FULL,confirm.getPaymentKey(),admin ? "ADMIN" : "USER",actorId));
                return new Prepared(id, confirm.getId(), null);
            }
            if (confirm != null || payment != null) return legacy(order, payment, actorId, "LEGACY_PENDING_PAYMENT");
            restore(order); order.cancel();
            return new Prepared(id, null, null);
        }
        if (payment == null || payment.getStatus() != PaymentStatus.SUCCESS)
            return legacy(order, payment, actorId, "LEGACY_PAID_PAYMENT");
        cancel = operations.save(new PaymentOperation(order, CANCEL_FULL, payment.getPaymentKey(), admin ? "ADMIN" : "USER", actorId));
        return new Prepared(id, cancel.getId(), null);
    }

    private Prepared legacy(Orders order, Payment payment, Long actorId, String code) {
        var op = new PaymentOperation(order, CANCEL_FULL, payment == null ? null : payment.getPaymentKey(), "ADMIN", actorId);
        op.manual(code); operations.save(op);
        return new Prepared(order.getId(), op.getId(), null);
    }

    public Claim claim(Long id) {
        Long orderId = operations.findOrderId(id).orElse(null);
        if (orderId == null) return null;
        Orders order = lockOrder(orderId);
        // Always lock CONFIRM before CANCEL_FULL, regardless of which worker wins.
        var confirm = operations.findLocked(orderId, CONFIRM).orElse(null);
        var cancel = operations.findLocked(orderId, CANCEL_FULL).orElse(null);
        PaymentOperation op = confirm != null && confirm.getId().equals(id) ? confirm : cancel;
        Instant now = Instant.now();
        if (op == null || !op.getId().equals(id) || !op.due(now)) return null;
        if (op.getType() == CANCEL_FULL && confirm != null && !confirm.terminal()) return null;
        boolean recovery = op.getFirstSentAt() != null || op.getStatus() != READY;
        boolean legacy = op.getLastErrorCode() != null && op.getLastErrorCode().startsWith("LEGACY_");
        boolean postAllowed = op.getAttemptCount() < 10 && (op.getFirstSentAt() == null
                || now.isBefore(op.getFirstSentAt().plus(Duration.ofDays(15))));
        op.claim(now, properties.lease(), recovery);
        em.flush();
        return new Claim(id, order.getId(), op.getType(), op.getPaymentKey(), op.getTossOrderId(), op.getAmount(),
                op.getReason(), op.getIdempotencyKey(), op.getLeaseToken(), op.getVersion(),
                recovery || op.getType() == CANCEL_FULL, postAllowed, legacy);
    }

    public void apply(Claim claim, GatewayPayment pg, boolean rejected, boolean manual, String code) {
        Orders order = lockOrder(claim.orderId());
        var confirm = operations.findLocked(order.getId(), CONFIRM).orElse(null);
        var cancel = operations.findLocked(order.getId(), CANCEL_FULL).orElse(null);
        PaymentOperation op = claim.type() == CONFIRM ? confirm : cancel;
        if (op == null || !op.owns(claim.token(), claim.version())) return;
        if (claim.legacy()) { op.manual("LEGACY_INCONSISTENCY"); return; }
        if (manual) { op.manual(code); return; }
        if (rejected) {
            op.reject();
            if (claim.type() == CONFIRM) {
                order.requestCancel(); restore(order); order.cancel();
                if (cancel != null) cancel.complete(null,null,null);
            }
            else order.rejectCancel();
            return;
        }
        if (pg == null || !pg.matches(op.getPaymentKey(),op.getTossOrderId(),op.getAmount())) {
            op.unknown("PG_UNKNOWN"); return;
        }
        var cancellation = pg.fullCancellation();
        if ("PARTIAL_CANCELED".equals(pg.status())) { op.manual("PARTIAL_CANCEL"); return; }
        if (claim.type() == CONFIRM) {
            if (!pg.approved() && cancellation == null) { op.unknown("PG_UNKNOWN"); return; }
            if (pg.approvedAt() == null || pg.method() == null || pg.method().isBlank() || pg.method().length() > 20) {
                op.manual("INVALID_APPROVAL"); return;
            }
            if (order.getStatus() != OrderStatus.PENDING || payments.findLockedByOrderId(order.getId()).isPresent()) {
                op.manual("LEGACY_APPROVAL_CONFLICT"); return;
            }
            int reward = mileagePolicy.calculateRewardMileage(order.getProductTotalPrice(),order.getUsedMileage());
            order.markAsPaid(reward); order.getMember().addMileage(reward);
            var payment = Payment.builder().orders(order).pgProvider("TOSSPAYMENTS").paymentKey(op.getPaymentKey())
                    .payMethod(pg.method()).status(PaymentStatus.SUCCESS).build();
            payment.recordApprovedAt(pg.approvedAt()); payments.save(payment);
            op.complete(pg.approvedAt(),null,null);
            if (order.isCancelRequested() || cancellation != null) {
                order.requestCancel();
                if (cancel == null) cancel = operations.save(new PaymentOperation(order, CANCEL_FULL, op.getPaymentKey(),
                        "USER", op.getActorId()));
                if (cancellation != null) finalizeCancel(order, payment, cancel, pg, cancellation);
            }
            sales.request(payment.getPaidAt());
        } else {
            if (cancellation == null) { op.unknown("PG_UNKNOWN"); return; }
            var payment = payments.findLockedByOrderId(order.getId()).orElse(null);
            if (order.getStatus() != OrderStatus.PAID || payment == null || payment.getStatus() != PaymentStatus.SUCCESS
                    || !Objects.equals(payment.getPaymentKey(),op.getPaymentKey())) {
                op.manual("LEGACY_REFUND_CONFLICT"); return;
            }
            finalizeCancel(order, payment, op, pg, cancellation);
            sales.request(payment.getPaidAt());
        }
    }

    private void finalizeCancel(Orders order, Payment payment, PaymentOperation op, GatewayPayment pg,
                                GatewayPayment.Cancellation cancellation) {
        restore(order); order.cancel(); order.requestCancel();
        payment.refund(cancellation.canceledAt());
        op.complete(pg.approvedAt(),cancellation.canceledAt(),cancellation.transactionKey());
    }

    private void restore(Orders order) {
        var quantities = new TreeMap<Long,Integer>();
        order.getOrderItems().forEach(i -> quantities.merge(i.getProduct().getId(),i.getQuantity(),Math::addExact));
        quantities.forEach((id,quantity) -> {
            var product = products.findLockedById(id).orElseThrow(() -> error(ErrorCode.PRODUCT_NOT_FOUND));
            em.refresh(product);
            if (products.increaseStock(id,quantity) != 1) throw error(ErrorCode.INVALID_INPUT_VALUE);
            em.refresh(product);
        });
        order.getMember().settleMileage(order.getUsedMileage(), order.getEarnedMileage());
    }

    public Prepared reconcile(Long id) {
        Orders order = lockOrder(id);
        var confirm = operations.findLocked(id, CONFIRM).orElse(null);
        var cancel = operations.findLocked(id, CANCEL_FULL).orElse(null);
        var op = cancel != null ? cancel : confirm;
        if (op == null) {
            var payment = payments.findLockedByOrderId(id).orElse(null);
            if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.CANCELED && payment != null)
                return legacy(order,payment,null,"LEGACY_NO_OPERATION");
            return new Prepared(id,null,ErrorCode.PAYMENT_OPERATION_CONFLICT);
        }
        if (confirm != null && !confirm.terminal()) op = confirm;
        op.scheduleReconcile();
        return new Prepared(id,op.getId(),null);
    }

    public Long nextCancellation(Long orderId) {
        Orders order = lockOrder(orderId);
        var confirm = operations.findLocked(orderId,CONFIRM).orElse(null);
        var cancel = operations.findLocked(orderId,CANCEL_FULL).orElse(null);
        if (confirm != null && !confirm.terminal()) return null;
        return order.isCancelRequested() && cancel != null && cancel.getStatus() == READY ? cancel.getId() : null;
    }

    public View view(Long id, Long requestedOp) {
        var order = orders.findById(id).orElseThrow(() -> error(ErrorCode.ORDER_NOT_FOUND));
        var op = requestedOp == null ? null : operations.findById(requestedOp).orElse(null);
        if (order.isCancelRequested() && (op == null || op.getStatus() != REJECTED && op.getStatus() != MANUAL_REQUIRED)) {
            op = order.getPaymentOperations().stream().filter(p -> p.getType() == CANCEL_FULL).findFirst().orElse(op);
        }
        var status = op == null ? null : op.getStatus();
        ErrorCode failure = status == REJECTED ? (op.getType() == CONFIRM ? ErrorCode.PAYMENT_CONFIRM_REJECTED
                : ErrorCode.PAYMENT_CANCEL_REJECTED) : status == MANUAL_REQUIRED ? ErrorCode.PAYMENT_OPERATION_CONFLICT : null;
        var payment = payments.findByOrdersId(id).map(PaymentDto.Response::new).orElse(null);
        return new View(new OrderDto.DetailResponse(order),payment,status,failure);
    }

    public OrderDto.DetailResponse ship(Long id, OrderDto.StatusUpdateRequest request) {
        Orders order = lockOrder(id);
        var confirm = operations.findLocked(id, CONFIRM).orElse(null);
        var cancel = operations.findLocked(id, CANCEL_FULL).orElse(null);
        if (request.getStatus() == OrderStatus.SHIPPED && order.getStatus() == OrderStatus.PAID) {
            if (request.getTrackingNo() == null || request.getTrackingNo().isBlank()) throw error(ErrorCode.INVALID_INPUT_VALUE);
            if (order.isCancelRequested() || confirm != null && !confirm.terminal() || cancel != null && !cancel.terminal())
                throw error(ErrorCode.PAYMENT_OPERATION_CONFLICT);
            var payment = payments.findLockedByOrderId(id).orElseThrow(() -> error(ErrorCode.PAYMENT_OPERATION_CONFLICT));
            if (payment.getStatus() != PaymentStatus.SUCCESS) throw error(ErrorCode.PAYMENT_OPERATION_CONFLICT);
        } else if (!(request.getStatus() == OrderStatus.DELIVERED && order.getStatus() == OrderStatus.SHIPPED))
            throw error(ErrorCode.INVALID_ORDER_STATUS);
        order.updateStatus(request.getStatus(),request.getTrackingNo());
        return new OrderDto.DetailResponse(order);
    }

    private Orders lockOrder(Long id) {
        Long member = orders.findMemberIdByOrderId(id).orElseThrow(() -> error(ErrorCode.ORDER_NOT_FOUND));
        var owner = members.findLockedById(member).orElseThrow(() -> error(ErrorCode.MEMBER_NOT_FOUND));
        em.refresh(owner);
        Orders order = orders.findLockedById(id).orElseThrow(() -> error(ErrorCode.ORDER_NOT_FOUND));
        em.refresh(order);
        if (!order.getMember().getId().equals(member)) throw error(ErrorCode.PAYMENT_OPERATION_CONFLICT);
        return order;
    }
    private void authorize(Orders order, Long member) {
        if (!order.getMember().getId().equals(member)) throw error(ErrorCode.ORDER_ACCESS_DENIED);
        if (order.getMember().getStatus() == MemberStatus.SUSPENDED) throw error(ErrorCode.SUSPENDED_MEMBER);
        if (order.getMember().getStatus() == MemberStatus.WITHDRAWN) throw error(ErrorCode.WITHDRAW_MEMBER);
    }
    private CustomException error(ErrorCode code) { return new CustomException(code); }
}

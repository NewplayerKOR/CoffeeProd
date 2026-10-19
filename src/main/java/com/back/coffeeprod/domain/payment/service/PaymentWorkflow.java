package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.back.coffeeprod.domain.payment.entity.PaymentOperation;
import com.back.coffeeprod.domain.payment.gateway.*;
import com.back.coffeeprod.domain.payment.repository.PaymentOperationRepository;
import com.back.coffeeprod.global.exception.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;

@Service @RequiredArgsConstructor @Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PaymentWorkflow {
    private final PaymentTransactions transactions;
    private final PaymentGateway gateway;
    private final PaymentOperationRepository operations;
    public record Result(int status, OrderDto.DetailResponse order, PaymentDto.Response payment) {}

    public Result confirm(Long member, PaymentDto.ConfirmRequest request) {
        return execute(transactions.prepareConfirm(member,request));
    }
    public Result cancel(Long actor, Long id, boolean admin) {
        return execute(transactions.prepareCancel(actor,id,admin));
    }
    public Result reconcile(Long id) { return execute(transactions.reconcile(id)); }
    private Result execute(PaymentTransactions.Prepared prepared) {
        if (prepared.error() != null) throw new CustomException(prepared.error());
        Long id = prepared.operationId();
        if (id != null) drive(id);
        Long cancellation = transactions.nextCancellation(prepared.orderId());
        if (cancellation != null) { drive(cancellation); id = cancellation; }
        var view = transactions.view(prepared.orderId(),id);
        if (view.error() != null) throw new CustomException(view.error());
        int status = view.status() == null || view.status() == PaymentOperation.Status.SUCCEEDED ? 200 : 202;
        return new Result(status,view.order(),view.payment());
    }
    public void recoverDue() {
        for (Long id : operations.findDueIds(Instant.now(), PageRequest.of(0,20))) {
            try {
                drive(id);
                Long orderId = operations.findOrderId(id).orElse(null);
                Long cancel = orderId == null ? null : transactions.nextCancellation(orderId);
                if (cancel != null) drive(cancel);
            } catch (RuntimeException e) { log.warn("payment recovery pending operationId={} code=RECOVERY_ERROR",id); }
        }
    }
    public void drive(Long id) {
        var claim = transactions.claim(id);
        if (claim == null) return;
        GatewayPayment pg = null;
        boolean rejected = false, manual = false;
        String code = "PG_UNKNOWN";
        try {
            if (claim.lookupFirst()) {
                pg = claim.paymentKey() == null ? gateway.lookupByOrderId(claim.tossOrderId())
                        : gateway.lookupByPaymentKey(claim.paymentKey());
                if (pg == null && claim.paymentKey() != null) pg = gateway.lookupByOrderId(claim.tossOrderId());
                if (claim.legacy()) { manual = true; code = "LEGACY_INCONSISTENCY"; }
                else if (pg != null && !pg.matches(claim.paymentKey(),claim.tossOrderId(),claim.amount())) {
                    manual = true; code = "PG_IDENTITY_MISMATCH";
                }
                else if (pg != null) {
                    if (claim.type() == PaymentOperation.Type.CANCEL_FULL && !pg.cardBased()) { manual = true; code = "UNSUPPORTED_METHOD"; }
                    else if ("PARTIAL_CANCELED".equals(pg.status())) { manual = true; code = "PARTIAL_CANCEL"; }
                    else if (claim.type() == PaymentOperation.Type.CONFIRM) {
                        if ("ABORTED".equals(pg.status()) || "EXPIRED".equals(pg.status())) rejected = true;
                        else if (!pg.approved() && pg.fullCancellation() == null
                                && ("READY".equals(pg.status()) || "IN_PROGRESS".equals(pg.status()))) {
                            if (claim.postAllowed()) pg = confirm(claim);
                            else { manual = true; code = "POST_LIMIT"; }
                        } else if (!pg.approved() && pg.fullCancellation() == null
                                && !"CANCELED".equals(pg.status())) {
                            manual = true; code = "UNSUPPORTED_STATUS";
                        }
                    } else if (pg.approved()) {
                        if (!pg.cardBased()) { manual = true; code = "UNSUPPORTED_METHOD"; }
                        else if (!claim.postAllowed()) { manual = true; code = "POST_LIMIT"; }
                        else pg = gateway.cancelFull(claim.paymentKey(),claim.reason(),claim.idempotencyKey());
                    } else if (pg.fullCancellation() == null && !"CANCELED".equals(pg.status())) {
                        manual = true; code = "UNSUPPORTED_STATUS";
                    }
                }
            } else pg = confirm(claim);
        } catch (GatewayException e) { rejected = e.isRejected(); }
        catch (RuntimeException e) { code = "PG_UNKNOWN"; }
        // Lookup may throw (404, timeout, 5xx). The POST limit also applies on that path.
        if (!manual && !rejected && !claim.postAllowed()
                && (pg == null || !pg.approved() && pg.fullCancellation() == null)) {
            manual = true;
            code = "POST_LIMIT";
        }
        try {
            transactions.apply(claim,pg,rejected,manual,code);
        } catch (RuntimeException e) {
            // PG may already have succeeded: leave the committed lease for lookup after expiry.
            log.warn("payment result pending operationId={} code=LOCAL_COMMIT_FAILED",id);
        }
    }
    private GatewayPayment confirm(PaymentTransactions.Claim c) {
        return gateway.confirm(c.paymentKey(),c.tossOrderId(),c.amount(),c.idempotencyKey());
    }
}

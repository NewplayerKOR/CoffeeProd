package com.back.coffeeprod.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payment.workers-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRecoveryScheduler {
    private final PaymentWorkflow workflow;
    @Scheduled(fixedDelayString = "${app.payment.recovery-delay:30000}")
    public void recover() { workflow.recoverDue(); }
}

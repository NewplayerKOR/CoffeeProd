package com.back.coffeeprod.domain.statistics.scheduler;

import com.back.coffeeprod.domain.statistics.repository.SalesRebuildRequestRepository;
import com.back.coffeeprod.domain.statistics.service.SalesRebuildService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payment.workers-enabled", havingValue = "true", matchIfMissing = true)
public class SalesRebuildScheduler {
    private final SalesRebuildRequestRepository repository;
    private final SalesRebuildService service;
    @Scheduled(fixedDelayString = "${app.statistics.rebuild-delay:30000}")
    public void rebuild() {
        for (var date : repository.findDates(PageRequest.of(0,20))) {
            try { service.process(date); }
            catch (RuntimeException e) { service.recordError(date); }
        }
    }
}

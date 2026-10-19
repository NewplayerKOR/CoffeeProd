package com.back.coffeeprod.domain.statistics.service;

import com.back.coffeeprod.domain.statistics.entity.SalesRebuildRequest;
import com.back.coffeeprod.domain.statistics.repository.SalesRebuildRequestRepository;
import com.back.coffeeprod.global.common.time.BusinessTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;

@Service @RequiredArgsConstructor @Slf4j
public class SalesRebuildService {
    private final SalesRebuildRequestRepository repository;
    private final StatisticsDateLock dateLock;
    private final SalesStatisticsService statistics;
    @Transactional
    public void request(Instant paidAt) {
        LocalDate date = paidAt.atZone(BusinessTime.SEOUL_ZONE).toLocalDate();
        dateLock.acquire(date);
        var request = repository.findById(date).orElseGet(() -> new SalesRebuildRequest(date));
        request.touch(); repository.save(request);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(LocalDate date) {
        dateLock.acquire(date);
        if (!repository.existsById(date)) return;
        statistics.aggregateDailySales(date);
        repository.deleteById(date);
        log.info("sales rebuild succeeded date={}", date);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(LocalDate date) {
        dateLock.acquire(date);
        repository.findById(date).ifPresent(SalesRebuildRequest::failed);
        log.warn("sales rebuild pending date={} code=SALES_REBUILD_FAILED", date);
    }
}

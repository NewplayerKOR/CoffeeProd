package com.back.coffeeprod.domain.statistics.scheduler;

import com.back.coffeeprod.domain.statistics.service.SalesStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
public class SalesStatisticsScheduler {

    private final SalesStatisticsService salesStatisticsService;

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    public void aggregateYesterdaySales() {
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        salesStatisticsService.aggregateDailySales(yesterday);
    }
}

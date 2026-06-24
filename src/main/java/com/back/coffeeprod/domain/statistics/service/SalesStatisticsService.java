package com.back.coffeeprod.domain.statistics.service;

import com.back.coffeeprod.domain.payment.entity.PaymentStatus;
import com.back.coffeeprod.domain.statistics.dto.SalesStatisticsDto;
import com.back.coffeeprod.domain.statistics.entity.SalesStatistics;
import com.back.coffeeprod.domain.statistics.entity.SalesStatisticsUnit;
import com.back.coffeeprod.domain.statistics.repository.PaymentSalesQueryRepository;
import com.back.coffeeprod.domain.statistics.repository.SalesAggregateRow;
import com.back.coffeeprod.domain.statistics.repository.SalesStatisticsRepository;
import com.back.coffeeprod.global.common.time.BusinessTime;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesStatisticsService {

    private final PaymentSalesQueryRepository paymentSalesQueryRepository;
    private final SalesStatisticsRepository salesStatisticsRepository;
    private static final int MAX_AGGREGATE_RANGE_DAYS = 366;

    @Transactional
    public void aggregateDailySales(LocalDate statDate) {
        aggregateDailySalesInternal(statDate);
    }

    @Transactional
    public SalesStatisticsDto.AggregateRangeResponse aggregateSalesRange(
            LocalDate from,
            LocalDate to
    ) {
        validateAggregateRange(from, to);

        int aggregateDays = Math.toIntExact(
                ChronoUnit.DAYS.between(from, to) + 1
        );

        LocalDate currentDate = from;

        while (!currentDate.isAfter(to)) {
            aggregateDailySalesInternal(currentDate);
            currentDate = currentDate.plusDays(1);
        }

        return new SalesStatisticsDto.AggregateRangeResponse(
                from,
                to,
                aggregateDays
        );
    }

    private void aggregateDailySalesInternal(LocalDate statDate) {
        Instant startAt = BusinessTime.startOfDay(statDate);
        Instant endAt = BusinessTime.startOfDay(statDate.plusDays(1));

        SalesAggregateRow row = paymentSalesQueryRepository.aggregatePaidSales(
                PaymentStatus.SUCCESS,
                startAt,
                endAt
        );

        SalesStatistics statistics = salesStatisticsRepository
                .findByStatDate(statDate)
                .orElseGet(() ->
                        new SalesStatistics(statDate, 0, 0, 0, 0, 0)
                );

        statistics.update(
                row.getOrderCount(),
                row.getProductSalesAmount(),
                row.getDeliveryFeeAmount(),
                row.getUsedMileageAmount(),
                row.getPaymentAmount()
        );

        salesStatisticsRepository.save(statistics);
    }

    // 기간 입력값을 검증함
    private void validateAggregateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new CustomException(
                    ErrorCode.INVALID_STATISTICS_DATE_RANGE
            );
        }

        long aggregateDays = ChronoUnit.DAYS.between(from, to) + 1;

        if (aggregateDays > MAX_AGGREGATE_RANGE_DAYS) {
            throw new CustomException(
                    ErrorCode.STATISTICS_DATE_RANGE_TOO_LARGE
            );
        }
    }

    public List<SalesStatisticsDto.Response> getSalesStatistics(
            LocalDate from,
            LocalDate to,
            SalesStatisticsUnit unit
    ) {
        List<SalesStatistics> statistics = salesStatisticsRepository
                .findByStatDateBetweenOrderByStatDateAsc(from, to);

        if (unit == SalesStatisticsUnit.DAILY) {
            return statistics.stream()
                    .map(stat -> new SalesStatisticsDto.Response(
                            stat.getStatDate().toString(),
                            stat.getOrderCount(),
                            stat.getProductSalesAmount(),
                            stat.getDeliveryFeeAmount(),
                            stat.getUsedMileageAmount(),
                            stat.getPaymentAmount()
                    ))
                    .toList();
        }

        Map<String, SalesStatisticsDto.Response> grouped = new LinkedHashMap<>();

        for (SalesStatistics stat : statistics) {
            String label = createLabel(stat.getStatDate(), unit);

            SalesStatisticsDto.Response current = grouped.get(label);
            if (current == null) {
                grouped.put(label, new SalesStatisticsDto.Response(
                        label,
                        stat.getOrderCount(),
                        stat.getProductSalesAmount(),
                        stat.getDeliveryFeeAmount(),
                        stat.getUsedMileageAmount(),
                        stat.getPaymentAmount()
                ));
                continue;
            }

            grouped.put(label, new SalesStatisticsDto.Response(
                    label,
                    current.getOrderCount() + stat.getOrderCount(),
                    current.getProductSalesAmount() + stat.getProductSalesAmount(),
                    current.getDeliveryFeeAmount() + stat.getDeliveryFeeAmount(),
                    current.getUsedMileageAmount() + stat.getUsedMileageAmount(),
                    current.getPaymentAmount() + stat.getPaymentAmount()
            ));
        }

        return grouped.values().stream().toList();
    }

    private String createLabel(LocalDate statDate, SalesStatisticsUnit unit) {
        if (unit == SalesStatisticsUnit.MONTHLY) {
            return YearMonth.from(statDate).toString();
        }

        return String.valueOf(statDate.getYear());
    }
}

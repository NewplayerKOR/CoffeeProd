package com.back.coffeeprod.domain.statistics.service;

import com.back.coffeeprod.domain.payment.entity.PaymentStatus;
import com.back.coffeeprod.domain.statistics.dto.SalesStatisticsDto;
import com.back.coffeeprod.domain.statistics.entity.SalesStatistics;
import com.back.coffeeprod.domain.statistics.entity.SalesStatisticsUnit;
import com.back.coffeeprod.domain.statistics.repository.PaymentSalesQueryRepository;
import com.back.coffeeprod.domain.statistics.repository.SalesAggregateRow;
import com.back.coffeeprod.domain.statistics.repository.SalesStatisticsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesStatisticsService {

    private final PaymentSalesQueryRepository paymentSalesQueryRepository;
    private final SalesStatisticsRepository salesStatisticsRepository;

    @Transactional
    public void aggregateDailySales(LocalDate statDate) {
        SalesAggregateRow row = paymentSalesQueryRepository.aggregatePaidSales(
                PaymentStatus.SUCCESS,
                statDate.atStartOfDay(),
                statDate.plusDays(1).atStartOfDay()
        );

        SalesStatistics statistics = salesStatisticsRepository.findByStatDate(statDate)
                .orElseGet(() -> new SalesStatistics(statDate, 0, 0, 0, 0, 0));

        statistics.update(
                row.getOrderCount(),
                row.getProductSalesAmount(),
                row.getDeliveryFeeAmount(),
                row.getUsedMileageAmount(),
                row.getPaymentAmount()
        );

        salesStatisticsRepository.save(statistics);
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

package com.back.coffeeprod.domain.statistics.dto;

import lombok.Getter;

import java.time.LocalDate;

public class SalesStatisticsDto {

    @Getter
    public static class Response {
        private final String label;
        private final long orderCount;
        private final long productSalesAmount;
        private final long deliveryFeeAmount;
        private final long usedMileageAmount;
        private final long paymentAmount;

        public Response(
                String label,
                long orderCount,
                long productSalesAmount,
                long deliveryFeeAmount,
                long usedMileageAmount,
                long paymentAmount
        ) {
            this.label = label;
            this.orderCount = orderCount;
            this.productSalesAmount = productSalesAmount;
            this.deliveryFeeAmount = deliveryFeeAmount;
            this.usedMileageAmount = usedMileageAmount;
            this.paymentAmount = paymentAmount;
        }
    }

    @Getter
    public static class AggregateRangeResponse {
        private final LocalDate from;
        private final LocalDate to;
        private final int aggregatedDays;

        public AggregateRangeResponse(
                LocalDate from,
                LocalDate to,
                int aggregateDays
        ) {
            this.from = from;
            this.to = to;
            this.aggregatedDays = aggregateDays;
        }
    }
}

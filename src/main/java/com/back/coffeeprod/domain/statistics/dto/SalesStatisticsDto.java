package com.back.coffeeprod.domain.statistics.dto;

import lombok.Getter;

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
}

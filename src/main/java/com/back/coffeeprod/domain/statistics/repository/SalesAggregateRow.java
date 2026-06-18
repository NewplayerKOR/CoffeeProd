package com.back.coffeeprod.domain.statistics.repository;

public interface SalesAggregateRow {

    long getOrderCount();

    long getProductSalesAmount();

    long getDeliveryFeeAmount();

    long getUsedMileageAmount();

    long getPaymentAmount();
}

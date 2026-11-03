package com.back.coffeeprod.domain.statistics.repository;

import java.time.LocalDate;

public interface DailySalesAggregateRow extends SalesAggregateRow {
    LocalDate getStatDate();
}

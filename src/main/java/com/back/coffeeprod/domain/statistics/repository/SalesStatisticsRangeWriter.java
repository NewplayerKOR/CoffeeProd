package com.back.coffeeprod.domain.statistics.repository;

import com.back.coffeeprod.domain.statistics.entity.SalesStatistics;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Repository
@RequiredArgsConstructor
public class SalesStatisticsRangeWriter {
    private static final int BATCH_SIZE = 50;
    private final EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(List<SalesStatistics> statistics) {
        Session session = em.unwrap(Session.class);
        Integer previousBatchSize = session.getJdbcBatchSize();
        try {
            session.setJdbcBatchSize(BATCH_SIZE);
            // 기존 행은 managed 상태를 유지하면서 갱신을 실제 JDBC 배치로 내보낸다.
            em.flush();
            var missing = statistics.stream().filter(row -> row.getId() == null).toList();
            for (int start = 0; start < missing.size(); start += BATCH_SIZE) {
                insert(missing.subList(start, Math.min(start + BATCH_SIZE, missing.size())));
            }
        } finally {
            session.setJdbcBatchSize(previousBatchSize);
        }
    }

    private void insert(List<SalesStatistics> rows) {
        // IDENTITY 키의 개별 persist 대신, ID가 필요 없는 신규 날짜를 다중 행 SQL로 저장한다.
        String values = IntStream.range(0, rows.size())
                .mapToObj(i -> "(:date" + i + ", :count" + i + ", :product" + i
                        + ", :delivery" + i + ", :mileage" + i + ", :payment" + i + ")")
                .collect(Collectors.joining(", "));
        var query = em.createNativeQuery("""
                INSERT INTO sales_statistics
                  (stat_date, order_count, product_sales_amount, delivery_fee_amount, used_mileage_amount, payment_amount)
                VALUES
                """ + values);
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            query.setParameter("date" + i, row.getStatDate());
            query.setParameter("count" + i, row.getOrderCount());
            query.setParameter("product" + i, row.getProductSalesAmount());
            query.setParameter("delivery" + i, row.getDeliveryFeeAmount());
            query.setParameter("mileage" + i, row.getUsedMileageAmount());
            query.setParameter("payment" + i, row.getPaymentAmount());
        }
        query.executeUpdate();
    }
}

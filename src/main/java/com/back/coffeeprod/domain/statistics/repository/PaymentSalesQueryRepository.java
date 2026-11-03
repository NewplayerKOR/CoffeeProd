package com.back.coffeeprod.domain.statistics.repository;

import com.back.coffeeprod.domain.payment.entity.Payment;
import com.back.coffeeprod.domain.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PaymentSalesQueryRepository extends JpaRepository<Payment, Long> {

    @Query("""
            SELECT
                COUNT(p.id) AS orderCount,
                COALESCE(SUM(o.productTotalPrice), 0) AS productSalesAmount,
                COALESCE(SUM(o.deliveryFee), 0) AS deliveryFeeAmount,
                COALESCE(SUM(o.usedMileage), 0) AS usedMileageAmount,
                COALESCE(SUM(o.totalPrice), 0) AS paymentAmount
            FROM Payment p
            JOIN p.orders o
            WHERE p.status = :status
              AND p.paidAt >= :startAt
              AND p.paidAt < :endAt
            """)
    SalesAggregateRow aggregatePaidSales(
            @Param("status") PaymentStatus status,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt
    );

    // 세션 시간대와 무관하게 KST 영업일로 묶어 기간 전체를 한 번에 집계한다.
    @Query(value = """
            SELECT CAST(p.paid_at AT TIME ZONE 'Asia/Seoul' AS DATE) AS "statDate",
                   COUNT(p.payment_id) AS "orderCount",
                   COALESCE(SUM(o.product_total_price), 0) AS "productSalesAmount",
                   COALESCE(SUM(o.delivery_fee), 0) AS "deliveryFeeAmount",
                   COALESCE(SUM(o.used_mileage), 0) AS "usedMileageAmount",
                   COALESCE(SUM(o.total_price), 0) AS "paymentAmount"
            FROM payment p JOIN orders o ON o.order_id = p.order_id
            WHERE p.status = :status AND p.paid_at >= :startAt AND p.paid_at < :endAt
            GROUP BY CAST(p.paid_at AT TIME ZONE 'Asia/Seoul' AS DATE)
            """, nativeQuery = true)
    List<DailySalesAggregateRow> aggregatePaidSalesByDate(
            @Param("status") String status,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt
    );
}

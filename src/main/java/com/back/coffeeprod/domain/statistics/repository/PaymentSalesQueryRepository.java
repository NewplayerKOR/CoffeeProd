package com.back.coffeeprod.domain.statistics.repository;

import com.back.coffeeprod.domain.payment.entity.Payment;
import com.back.coffeeprod.domain.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

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
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );
}

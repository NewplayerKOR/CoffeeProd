package com.back.coffeeprod.domain.statistics.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "sales_statistics",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sales_statistics_stat_date", columnNames = "stat_date")
        }
)
public class SalesStatistics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sales_statistics_id")
    private Long id;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate; // 집계 기준일

    @Column(nullable = false)
    private long orderCount;    // 결제 완료 주문 수

    @Column(nullable = false)
    private long productSalesAmount;    // 상품 매출 합계

    @Column(nullable = false)
    private long deliveryFeeAmount; // 배송비 합계

    @Column(nullable = false)
    private long usedMileageAmount; // 사용 마일리지 합계

    @Column(nullable = false)
    private long paymentAmount; // 실제 결제 금액 합계

    public SalesStatistics(
            LocalDate statDate,
            long orderCount,
            long productSalesAmount,
            long deliveryFeeAmount,
            long usedMileageAmount,
            long paymentAmount
    ) {
        this.statDate = statDate;
        this.orderCount = orderCount;
        this.productSalesAmount = productSalesAmount;
        this.deliveryFeeAmount = deliveryFeeAmount;
        this.usedMileageAmount = usedMileageAmount;
        this.paymentAmount = paymentAmount;
    }

    public void update(
            long orderCount,
            long productSalesAmount,
            long deliveryFeeAmount,
            long usedMileageAmount,
            long paymentAmount
    ) {
        this.orderCount = orderCount;
        this.productSalesAmount = productSalesAmount;
        this.deliveryFeeAmount = deliveryFeeAmount;
        this.usedMileageAmount = usedMileageAmount;
        this.paymentAmount = paymentAmount;
    }
}

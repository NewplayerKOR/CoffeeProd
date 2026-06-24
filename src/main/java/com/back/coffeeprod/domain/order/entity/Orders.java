package com.back.coffeeprod.domain.order.entity;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "orders")
public class Orders extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "order_data", nullable = false)
    private LocalDateTime orderDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column
    private int usedMileage;    // 사용한 마일리지

    @Column(nullable = false)
    private String deliveryAddress; // 주소 스냅샷

    @Column
    private String trackingNo;  // 운송장 번호

    // 주문 - 주문 상품 (1 : N)
    @OneToMany(mappedBy = "orders", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    @Column(name = "toss_order_id", nullable = false, unique = true, length = 64)
    private String tossOrderId;

    @Column(nullable = false)
    private int productTotalPrice; // 배송비와 마일리지 차감 전 상품 총액

    @Column(nullable = false)
    private int deliveryFee; // 주문 당시 배송비 스냅샷

    @Column(nullable = false)
    private int earnedMileage; // 결제 완료 후 적립된 마일리지

    @Column(nullable = false)
    private int totalPrice; // 최종 결제 금액 = 상품금액 - 사용마일리지 + 배송비

    @Builder
    public Orders(
            Member member,
            String tossOrderId,
            int productTotalPrice,
            int deliveryFee,
            int totalPrice,
            int usedMileage,
            String deliveryAddress
    ) {
        this.member = member;
        this.tossOrderId = tossOrderId;
        this.productTotalPrice = productTotalPrice;
        this.deliveryFee = deliveryFee;
        this.earnedMileage = 0;
        this.totalPrice = totalPrice;
        this.usedMileage = usedMileage;
        this.deliveryAddress = deliveryAddress;
        this.orderDate = LocalDateTime.now();
        this.status = OrderStatus.PENDING;
    }

    // 결제 완료 처리
    public void markAsPaid(int earnedMileage) {
        this.status = OrderStatus.PAID;
        this.earnedMileage = earnedMileage;
    }

    // 주문 취소
    public void cancel() {
        // PENDING, PAID 상태만 취소 가능
        if (this.status != OrderStatus.PENDING && this.status != OrderStatus.PAID) {
            throw new IllegalStateException("취소할 수 없는 주문 상태입니다.");
        }
        this.status = OrderStatus.CANCELED;
    }

    // 배송 상태 변경 + 운송장 등록 (관리자)
    public void updateStatus(OrderStatus status, String trackingNo) {
        this.status = status;
        if (trackingNo != null && !trackingNo.isBlank()) {
            this.trackingNo = trackingNo;
        }
    }
}

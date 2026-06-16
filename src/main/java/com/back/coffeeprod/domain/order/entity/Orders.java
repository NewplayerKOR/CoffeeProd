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
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(name = "order_data")
    private LocalDateTime orderDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private int totalPrice;     // 할인 적용 후 최종 금액

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

    @Builder
    public Orders(Member member, String tossOrderId, int totalPrice, int usedMileage, String deliveryAddress) {
        this.member = member;
        this.tossOrderId = tossOrderId;
        this.orderDate = LocalDateTime.now();
        this.status = OrderStatus.PENDING;  // 기본 상태: 임시 생성
        this.totalPrice = totalPrice;
        this.usedMileage = usedMileage;
        this.deliveryAddress = deliveryAddress;
    }

    // 결제 완료 처리
    public void markAsPaid() {
        this.status = OrderStatus.PAID;
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

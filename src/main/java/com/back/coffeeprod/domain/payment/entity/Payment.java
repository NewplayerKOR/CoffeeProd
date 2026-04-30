package com.back.coffeeprod.domain.payment.entity;

import com.back.coffeeprod.domain.order.entity.Orders;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long id;

    // 주문 - 결제 (1:1)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Orders orders;

    @Column(nullable = false, length = 50)
    private String pgProvider;  // 결제 대행사 (TOSSPAYMENTS 등)

    @Column(nullable = false)
    private String paymentKey;  // 고유 결제 키

    @Column(nullable = false, length = 20)
    private String payMethod;   // 결제 수단 (CARD, VIRTUAL_ACCOUNT 등)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column
    private LocalDateTime paidAt;   // 결제 완료 시각

    @Builder
    public Payment(Orders orders, String pgProvider, String paymentKey, String payMethod, PaymentStatus status) {
        this.orders = orders;
        this.pgProvider = pgProvider;
        this.paymentKey = paymentKey;
        this.payMethod = payMethod;
        this.status = status;
        this.paidAt = LocalDateTime.now();
    }

    // 환불 처리
    public void refund() {
        this.status = PaymentStatus.REFUNDED;
    }
}

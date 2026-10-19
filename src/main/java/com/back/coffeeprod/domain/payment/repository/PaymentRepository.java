package com.back.coffeeprod.domain.payment.repository;

import com.back.coffeeprod.domain.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Payment p where p.orders.id = :id")
    Optional<Payment> findLockedByOrderId(@org.springframework.data.repository.query.Param("id") Long id);

    // 주문 ID로 결제 정보 조회
    Optional<Payment> findByOrdersId(Long orderId);

    // 토스 paymentKey로 결제 정보 조회 (환불/취소 시 사용)
    Optional<Payment> findByPaymentKey(String paymentKey);
}

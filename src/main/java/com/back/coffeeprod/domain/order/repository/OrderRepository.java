package com.back.coffeeprod.domain.order.repository;

import com.back.coffeeprod.domain.order.entity.Orders;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Orders, Long> {

    // 내 주문 목록 조회 (페이지네이션)
    // OrderItem, Product를 JOIN FETCH -> N + 1 방지
    @Query("""
            SELECT DISTINCT o FROM Orders o
            JOIN FETCH o.member
            JOIN FETCH o.orderItems oi
            JOIN FETCH oi.product
            WHERE o.member.id = :memberId
            ORDER BY o.orderDate DESC
            """)
    Page<Orders> findByMemberIdWithItems(
            @Param("memberId") Long memberId, Pageable pageable);

    // 주문 상세 조회 (단건)
    @Query("""
            SELECT o FROM Orders o
            JOIN FETCH o.member
            JOIN FETCH o.orderItems oi
            JOIN FETCH oi.product
            WHERE o.id = :orderId
            """)
    java.util.Optional<Orders> findByIdWithItems(@Param("orderId") Long orderId);

    // 관리자 전체 주문 목록 조회 (페이지네이션)
    @Query("""
            SELECT DISTINCT o FROM Orders o
            JOIN FETCH o.member
            JOIN FETCH o.orderItems oi
            JOIN FETCH oi.product
            ORDER BY o.orderDate DESC
            """)
    Page<Orders> findAllWithItems(Pageable pageable);
}

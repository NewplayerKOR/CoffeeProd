package com.back.coffeeprod.domain.cart.repository;

import com.back.coffeeprod.domain.cart.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    // 회원 ID로 장바구니 조회
    @Query("""
            SELECT c FROM Cart c
            LEFT JOIN FETCH c.cartItems ci
            LEFT JOIN FETCH ci.product
            WHERE c.member.id = :member_id
            """)
    Optional<Cart> findByMemberIdWithItems(@Param("member_id") Long memberId);

    // 단순 존재 여부 확인용 (장바구니 생성 여부 체크)
    Optional<Cart> findByMemberId(Long memberId);
}

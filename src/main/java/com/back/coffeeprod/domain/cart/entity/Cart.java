package com.back.coffeeprod.domain.cart.entity;

import com.back.coffeeprod.domain.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "cart")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cart_id")
    private Long id;

    // 회원 1명 = 장바구니 1개 (1:1 관계)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", unique = true)
    private Member member;

    // 장바구니 - 장바구니 상품 (1:N 관계)
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> cartItems = new ArrayList<>();

    @Builder
    public Cart(Member member) {
        this.member = member;
    }

    // 장바구니 전체 비우기
    public void clear() {
        this.cartItems.clear();
    }

}

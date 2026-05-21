package com.back.coffeeprod.domain.order.entity;

import com.back.coffeeprod.domain.cart.entity.GrindType;
import com.back.coffeeprod.domain.product.entity.Product;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Orders orders;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false)
    private int orderPrice;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GrindType grindType;

    @Builder
    public OrderItem(Orders orders, Product product, int orderPrice, int quantity, GrindType grindType) {
        this.orders = orders;
        this.product = product;
        this.orderPrice = orderPrice;   // 현재 상품 가격을 스냅샷으로 저장
        this.quantity = quantity;
        this.grindType = grindType;
    }

    // 소계 계산
    public int getSubTotal() {
        return this.orderPrice * this.quantity;
    }
}

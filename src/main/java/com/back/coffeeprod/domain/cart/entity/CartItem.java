package com.back.coffeeprod.domain.cart.entity;

import com.back.coffeeprod.domain.product.entity.Product;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "cart_item")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cart_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id")
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GrindType grindType;

    @Builder
    public CartItem(Cart cart, Product product, int quantity, GrindType grindType) {
        this.cart = cart;
        this.product = product;
        this.quantity = quantity;
        this.grindType = grindType;
    }

    public void update(int quantity, GrindType grindType) {
        this.quantity = quantity;
        this.grindType = grindType;
    }

    public void addQuantity(int quantity) {
        this.quantity += quantity;
    }
}

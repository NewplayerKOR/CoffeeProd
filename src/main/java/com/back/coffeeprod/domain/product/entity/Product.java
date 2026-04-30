package com.back.coffeeprod.domain.product.entity;

import com.back.coffeeprod.global.common.entity.BaseTimeEntity;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product")
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false)
    private int stockQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoastLevel roastLevel;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;


    @Builder
    public Product(Category category, String name, int price, int stockQuantity,
                   RoastLevel roastLevel, String description, String imageUrl) {
        this.category = category;
        this.name = name;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.roastLevel = roastLevel;
        this.description = description;
        this.imageUrl = imageUrl;
        this.status = ProductStatus.ON_SALE;    // 기본 상태: 판매 중
    }

    // 상품 전체 정보 수정
    public void update(Category category, String name, int price, int stockQuantity,
                       RoastLevel roastLevel, String description, String imageUrl) {
        this.category = category;
        this.name = name;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.roastLevel = roastLevel;
        this.description = description;
        this.imageUrl = imageUrl;
    }

    // 상품 상태 변경 (수정 / 품절)
    public void updateStatus(ProductStatus status) {
        this.status = status;
    }

    // 재고 차감 (주문 시 호출) - 동시성 제어 필요
    public void decreaseStock(int quantity) {
        if (this.stockQuantity < quantity) {
            throw new CustomException(ErrorCode.OUT_OF_STOCK);
        }
        this.stockQuantity -= quantity;
    }

    // 재고 추가 (관리자 입고 시 호출)
    public void addStock(int quantity) {
        this.stockQuantity += quantity;
    }
}

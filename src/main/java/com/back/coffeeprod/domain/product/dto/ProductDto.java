package com.back.coffeeprod.domain.product.dto;

import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class ProductDto {

    // 상품 등록/수정 요청 DTO(관리자용)
    @Getter
    @NoArgsConstructor
    public static class Request {
        private Long categoryId;
        private String name;
        private int price;
        private int stockQuantity;
        private RoastLevel roastLevel;
        private String description;
        private String image_url;
    }

    // 상품 목록 조회용 응답 DTO (요약 정보)
    @Getter
    public static class SummaryResponse {
        private final Long id;
        private final String categoryName;
        private final String name;
        private final int price;
        private final RoastLevel roastLevel;
        private final String imageUrl;
        private final ProductStatus status;

        public SummaryResponse(Product product) {
            this.id = product.getId();
            this.categoryName = product.getCategory().getName();
            this.name = product.getName();
            this.price = product.getPrice();
            this.roastLevel = product.getRoastLevel();
            this.imageUrl = product.getImage_url();
            this.status = product.getStatus();
        }
    }

    // 상품 상세 조회용 응답 DTO (전체정보)
    @Getter
    public static class DetailResponse {
        private final Long id;
        private final Long categoryId;
        private final String categoryName;
        private final String name;
        private final int price;
        private final int stockQuantity;
        private final RoastLevel roastLevel;
        private final String description;
        private final String imageUrl;
        private final ProductStatus status;

        public DetailResponse(Product product) {
            this.id = product.getId();
            this.categoryId = product.getCategory().getId();
            this.categoryName = product.getCategory().getName();
            this.name = product.getName();
            this.price = product.getPrice();
            this.stockQuantity = product.getStockQuantity();
            this.roastLevel = product.getRoastLevel();
            this.description = product.getDescription();
            this.imageUrl = product.getImage_url();
            this.status = product.getStatus();
        }
    }

    // 상태 변경 요청 DTO (관리자용)
    @Getter
    @NoArgsConstructor
    public static class StatusRequest {
        private ProductStatus status;
    }

    // 재고 추가 요청 DTO (관리자용)
    @Getter
    @NoArgsConstructor
    public static class StockRequest {
        private int quantity;
    }
}

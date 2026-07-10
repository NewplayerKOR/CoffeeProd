package com.back.coffeeprod.domain.product.dto;

import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class ProductDto {

    // 상품 등록/수정 요청 DTO(관리자용)
    @Getter
    @NoArgsConstructor
    public static class Request {

        @NotNull(message = "카테고리 ID는 필수입니다.")
        private Long categoryId;

        private Long coffeeProfileId;

        @NotBlank(message = "상품명은 필수입니다.")
        @Size(max = 100, message = "상품명은 100자 이하로 입력해야 합니다.")
        private String name;

        @Positive(message = "상품 가격은 1원 이상이어야 합니다.")
        private int price;

        @PositiveOrZero(message = "재고 수량은 0 이상이어야 합니다.")
        private int stockQuantity;

        @NotNull(message = "로스트 단계는 필수입니다.")
        private RoastLevel roastLevel;

        private String description;

        @Size(max = 500, message = "이미지 URL은 500자 이하로 입력해야 합니다.")
        private String image_url;
    }

    // 상품 목록 조회용 응답 DTO (요약 정보)
    @Getter
    public static class SummaryResponse {
        private final Long id;
        private final String categoryName;
        private final Long coffeeProfileId;
        private final String coffeeProfileName;
        private final String name;
        private final int price;
        private final RoastLevel roastLevel;
        private final String imageUrl;
        private final ProductStatus status;

        public SummaryResponse(Product product) {
            this.id = product.getId();
            this.categoryName = product.getCategory().getName();
            this.coffeeProfileId = product.getCoffeeProfile() == null
                    ? null
                    : product.getCoffeeProfile().getId();
            this.coffeeProfileName = product.getCoffeeProfile() == null
                    ? null
                    : product.getCoffeeProfile().getProfileName();
            this.name = product.getName();
            this.price = product.getPrice();
            this.roastLevel = product.getRoastLevel();
            this.imageUrl = product.getImageUrl();
            this.status = product.getStatus();
        }
    }

    // 상품 상세 조회용 응답 DTO (전체정보)
    @Getter
    public static class DetailResponse {
        private final Long id;
        private final Long categoryId;
        private final String categoryName;
        private final CoffeeProfileSummary coffeeProfile;
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
            this.coffeeProfile = product.getCoffeeProfile() == null
                    ? null
                    : new CoffeeProfileSummary(product.getCoffeeProfile());
            this.name = product.getName();
            this.price = product.getPrice();
            this.stockQuantity = product.getStockQuantity();
            this.roastLevel = product.getRoastLevel();
            this.description = product.getDescription();
            this.imageUrl = product.getImageUrl();
            this.status = product.getStatus();
        }
    }

    // 상품 상세에서 사용할 커피 프로필 요약 DTO
    @Getter
    public static class CoffeeProfileSummary {
        private final Long id;
        private final String profileName;
        private final BeanType beanType;
        private final String processingMethodName;
        private final String originCountryCode;
        private final String originRegion;
        private final RoastLevel roastLevel;
        private final boolean decaf;
        private final short acidity;
        private final short body;
        private final short sweetness;
        private final short aroma;
        private final String summary;

        public CoffeeProfileSummary(CoffeeProfile coffeeProfile) {
            this.id = coffeeProfile.getId();
            this.profileName = coffeeProfile.getProfileName();
            this.beanType = coffeeProfile.getBeanType();
            this.processingMethodName = coffeeProfile.getProcessingMethod() == null
                    ? null
                    : coffeeProfile.getProcessingMethod().getName();
            this.originCountryCode = coffeeProfile.getOriginCountryCode();
            this.originRegion = coffeeProfile.getOriginRegion();
            this.roastLevel = coffeeProfile.getRoastLevel();
            this.decaf = coffeeProfile.isDecaf();
            this.acidity = coffeeProfile.getAcidity();
            this.body = coffeeProfile.getBody();
            this.sweetness = coffeeProfile.getSweetness();
            this.aroma = coffeeProfile.getAroma();
            this.summary = coffeeProfile.getSummary();
        }
    }

    // 상태 변경 요청 DTO (관리자용)
    @Getter
    @NoArgsConstructor
    public static class StatusRequest {

        @NotNull(message = "상품 상태는 필수입니다.")
        private ProductStatus status;
    }

    // 재고 추가 요청 DTO (관리자용)
    @Getter
    @NoArgsConstructor
    public static class StockRequest {

        @Positive(message = "추가할 재고 수량은 1 이상이어야 합니다.")
        private int quantity;
    }
}

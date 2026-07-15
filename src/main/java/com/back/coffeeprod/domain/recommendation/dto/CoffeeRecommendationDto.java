package com.back.coffeeprod.domain.recommendation.dto;

import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeProfileDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.recommendation.entity.MemberCoffeePreference;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

public class CoffeeRecommendationDto {

    // 고객 커피 취향 조건을 받음
    @Getter
    @NoArgsConstructor
    public static class Request {

        private RoastLevel roastLevel;

        private BeanType beanType;

        @Positive(message = "가공 방식 ID는 양수여야 합니다.")
        private Long processingMethodId;

        private Boolean decaf;

        @Min(value = 1, message = "선호 산미 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "선호 산미 점수는 5 이하여야 합니다.")
        private Integer preferredAcidity;

        @Min(value = 1, message = "선호 바디 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "선호 바디 점수는 5 이하여야 합니다.")
        private Integer preferredBody;

        @Min(value = 1, message = "선호 단맛 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "선호 단맛 점수는 5 이하여야 합니다.")
        private Integer preferredSweetness;

        @Min(value = 1, message = "선호 향 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "선호 향 점수는 5 이하여야 합니다.")
        private Integer preferredAroma;

        @Min(value = 1, message = "추천 개수는 1개 이상이어야 합니다.")
        @Max(value = 10, message = "추천 개수는 최대 10개입니다.")
        private Integer limit;

        // 저장된 회원 취향을 추천 조건으로 변환함
        public static Request from(
                MemberCoffeePreference preference,
                Integer limit
        ) {
            Request request = new Request();

            request.roastLevel = preference.getRoastLevel();
            request.beanType = preference.getBeanType();
            request.processingMethodId = preference.getProcessingMethod() == null
                    ? null
                    : preference.getProcessingMethod().getId();
            request.decaf = preference.getDecaf();
            request.preferredAcidity = toInteger(
                    preference.getPreferredAcidity()
            );
            request.preferredBody = toInteger(
                    preference.getPreferredBody()
            );
            request.preferredSweetness = toInteger(
                    preference.getPreferredSweetness()
            );
            request.preferredAroma = toInteger(
                    preference.getPreferredAroma()
            );
            request.limit = limit;

            return request;
        }

        // Short 점수를 Integer 점수로 변환함
        private static Integer toInteger(Short value) {
            return value == null ? null : value.intValue();
        }
    }

    // 추천 결과를 반환함
    @Getter
    public static class Response {
        private final Long productId;
        private final String categoryName;
        private final String sku;
        private final String name;
        private final int price;
        private final int weightGrams;
        private final String imageUrl;

        private final Long coffeeProfileId;
        private final String coffeeProfileName;
        private final String processingMethodName;
        private final RoastLevel roastLevel;
        private final BeanType beanType;
        private final boolean decaf;
        private final short acidity;
        private final short body;
        private final short sweetness;
        private final short aroma;
        private final List<CoffeeProfileDto.FlavorNoteResponse> flavorNotes;
        private final List<CoffeeProfileDto.BrewMethodResponse> brewMethods;

        private final int recommendationScore;
        private final List<String> reasons;

        public Response(Product product, int recommendationScore, List<String> reasons) {
            CoffeeProfile coffeeProfile = product.getCoffeeProfile();

            this.productId = product.getId();
            this.categoryName = product.getCategory().getName();
            this.sku = product.getSku();
            this.name = product.getName();
            this.price = product.getPrice();
            this.weightGrams = product.getWeightGrams();
            this.imageUrl = product.getImageUrl();

            this.coffeeProfileId = coffeeProfile.getId();
            this.coffeeProfileName = coffeeProfile.getProfileName();
            this.processingMethodName = coffeeProfile.getProcessingMethod() == null
                    ? null
                    : coffeeProfile.getProcessingMethod().getName();
            this.roastLevel = coffeeProfile.getRoastLevel();
            this.beanType = coffeeProfile.getBeanType();
            this.decaf = coffeeProfile.isDecaf();
            this.acidity = coffeeProfile.getAcidity();
            this.body = coffeeProfile.getBody();
            this.sweetness = coffeeProfile.getSweetness();
            this.aroma = coffeeProfile.getAroma();
            this.flavorNotes = coffeeProfile.getFlavorNotes().stream()
                    .map(CoffeeProfileDto.FlavorNoteResponse::new)
                    .toList();
            this.brewMethods = coffeeProfile.getBrewMethods().stream()
                    .map(CoffeeProfileDto.BrewMethodResponse::new)
                    .toList();

            this.recommendationScore = recommendationScore;
            this.reasons = List.copyOf(reasons);
        }
    }
}

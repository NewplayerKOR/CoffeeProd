package com.back.coffeeprod.domain.coffeeprofile.dto;

import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfileBrewMethod;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfileFlavorNote;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfileVariety;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.global.common.time.BusinessTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class CoffeeProfileDto {

    @Getter
    @NoArgsConstructor
    public static class Request {

        private Long processingMethodId;

        @NotBlank(message = "프로필명은 필수입니다.")
        @Size(max = 150, message = "프로필명은 150자 이하여야 합니다.")
        private String profileName;

        @NotNull(message = "원두 구성은 필수입니다.")
        private BeanType beanType;

        @Pattern(
                regexp = "^[A-Z]{2}$",
                message = "원산지 국가 코드는 영문 대문자 2자리여야 합니다."
        )
        private String originCountryCode;

        @Size(max = 100, message = "원산지 지역은 100자 이하여야 합니다.")
        private String originRegion;

        @Size(max = 150, message = "농장 또는 조합명은 150자 이하여야 합니다.")
        private String farmOrCooperative;

        @Size(max = 150, message = "생산자명은 150자 이하여야 합니다.")
        private String producer;

        @PositiveOrZero(message = "최저 고도는 0 이상이어야 합니다.")
        private Integer altitudeMin;

        @PositiveOrZero(message = "최고 고도는 0 이상이어야 합니다.")
        private Integer altitudeMax;

        @NotNull(message = "로스팅 단계는 필수입니다.")
        private RoastLevel roastLevel;

        @NotNull(message = "디카페인 여부는 필수입니다.")
        private Boolean decaf;

        @Size(max = 50, message = "디카페인 방식은 50자 이하여야 합니다.")
        private String decafMethod;

        @NotNull(message = "산미 점수는 필수입니다.")
        @Min(value = 1, message = "산미 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "산미 점수는 5 이하여야 합니다.")
        private Short acidity;

        @NotNull(message = "바디 점수는 필수입니다.")
        @Min(value = 1, message = "바디 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "바디 점수는 5 이하여야 합니다.")
        private Short body;

        @NotNull(message = "단맛 점수는 필수입니다.")
        @Min(value = 1, message = "단맛 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "단맛 점수는 5 이하여야 합니다.")
        private Short sweetness;

        @NotNull(message = "향 점수는 필수입니다.")
        @Min(value = 1, message = "향 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "향 점수는 5 이하여야 합니다.")
        private Short aroma;

        private String summary;

        // 배열 순서로 향미 노트 우선순위를 지정함
        @NotNull(message = "향미 노트 목록은 null일 수 없습니다.")
        @Size(max = 5, message = "향미 노트는 최대 5개까지 등록할 수 있습니다.")
        @Valid
        private List<FlavorNoteRequest> flavorNotes = new ArrayList<>();

        // 배열 순서로 추천 추출법 우선순위를 지정함
        @NotNull(message = "추천 추출법 목록은 null일 수 없습니다.")
        @Size(max = 3, message = "추천 추출법은 최대 3개까지 등록할 수 있습니다.")
        @Valid
        private List<BrewMethodRequest> brewMethods = new ArrayList<>();

        // 배열 순서로 품종 노출 순서를 지정함
        @NotNull(message = "커피 품종 목록은 null일 수 없습니다.")
        @Size(max = 3, message = "커피 품종은 최대 3개까지 등록할 수 있습니다.")
        @Valid
        private List<VarietyRequest> varieties = new ArrayList<>();
    }

    @Getter
    @NoArgsConstructor
    public static class FlavorNoteRequest {

        @NotNull(message = "향미 노트 ID는 필수입니다.")
        @Positive(message = "향미 노트 ID는 양수여야 합니다.")
        private Long flavorNoteId;

        @NotNull(message = "향미 강도는 필수입니다.")
        @Min(value = 1, message = "향미 강도는 1 이상이어야 합니다.")
        @Max(value = 5, message = "향미 강도는 5 이하여야 합니다.")
        private Short intensity;
    }

    @Getter
    @NoArgsConstructor
    public static class BrewMethodRequest {

        @NotNull(message = "추천 추출법 ID는 필수입니다.")
        @Positive(message = "추천 추출법 ID는 양수여야 합니다.")
        private Long brewMethodId;

        @Size(max = 500, message = "추출 안내는 500자 이하여야 합니다.")
        private String recommendationNote;
    }

    @Getter
    @NoArgsConstructor
    public static class VarietyRequest {

        @NotNull(message = "커피 품종 ID는 필수입니다.")
        @Positive(message = "커피 품종 ID는 양수여야 합니다.")
        private Long coffeeVarietyId;
    }

    @Getter
    public static class Response {
        private final Long id;
        private final String profileName;
        private final BeanType beanType;
        private final ProcessingMethodDto.Response processingMethod;
        private final String originCountryCode;
        private final String originRegion;
        private final String farmOrCooperative;
        private final String producer;
        private final Integer altitudeMin;
        private final Integer altitudeMax;
        private final RoastLevel roastLevel;
        private final boolean decaf;
        private final String decafMethod;
        private final short acidity;
        private final short body;
        private final short sweetness;
        private final short aroma;
        private final String summary;
        private final List<FlavorNoteResponse> flavorNotes;
        private final List<BrewMethodResponse> brewMethods;
        private final List<VarietyResponse> varieties;
        private final OffsetDateTime createdAt;
        private final OffsetDateTime updatedAt;

        public Response(CoffeeProfile profile) {
            this.id = profile.getId();
            this.profileName = profile.getProfileName();
            this.beanType = profile.getBeanType();
            this.processingMethod = profile.getProcessingMethod() == null
                    ? null
                    : new ProcessingMethodDto.Response(
                    profile.getProcessingMethod()
            );
            this.originCountryCode = profile.getOriginCountryCode();
            this.originRegion = profile.getOriginRegion();
            this.farmOrCooperative = profile.getFarmOrCooperative();
            this.producer = profile.getProducer();
            this.altitudeMin = profile.getAltitudeMin();
            this.altitudeMax = profile.getAltitudeMax();
            this.roastLevel = profile.getRoastLevel();
            this.decaf = profile.isDecaf();
            this.decafMethod = profile.getDecafMethod();
            this.acidity = profile.getAcidity();
            this.body = profile.getBody();
            this.sweetness = profile.getSweetness();
            this.aroma = profile.getAroma();
            this.summary = profile.getSummary();
            this.flavorNotes = profile.getFlavorNotes().stream()
                    .map(FlavorNoteResponse::new)
                    .toList();
            this.brewMethods = profile.getBrewMethods().stream()
                    .map(BrewMethodResponse::new)
                    .toList();
            this.varieties = profile.getVarieties().stream()
                    .map(VarietyResponse::new)
                    .toList();
            this.createdAt = BusinessTime.toSeoul(profile.getCreatedAt());
            this.updatedAt = BusinessTime.toSeoul(profile.getUpdatedAt());
        }
    }

    @Getter
    public static class VarietyResponse {
        private final Long coffeeVarietyId;
        private final String code;
        private final String name;
        private final String description;

        public VarietyResponse(
                CoffeeProfileVariety profileVariety
        ) {
            this.coffeeVarietyId = profileVariety.getCoffeeVariety().getId();
            this.code = profileVariety.getCoffeeVariety().getCode();
            this.name = profileVariety.getCoffeeVariety().getName();
            this.description = profileVariety.getCoffeeVariety()
                    .getDescription();
        }
    }

    @Getter
    public static class FlavorNoteResponse {
        private final Long flavorNoteId;
        private final String code;
        private final String name;
        private final String description;
        private final short intensity;

        public FlavorNoteResponse(
                CoffeeProfileFlavorNote profileFlavorNote
        ) {
            this.flavorNoteId = profileFlavorNote.getFlavorNote().getId();
            this.code = profileFlavorNote.getFlavorNote().getCode();
            this.name = profileFlavorNote.getFlavorNote().getName();
            this.description = profileFlavorNote.getFlavorNote()
                    .getDescription();
            this.intensity = profileFlavorNote.getIntensity();
        }
    }

    @Getter
    public static class BrewMethodResponse {
        private final Long brewMethodId;
        private final String code;
        private final String name;
        private final String description;
        private final String recommendationNote;

        public BrewMethodResponse(
                CoffeeProfileBrewMethod profileBrewMethod
        ) {
            this.brewMethodId = profileBrewMethod.getBrewMethod().getId();
            this.code = profileBrewMethod.getBrewMethod().getCode();
            this.name = profileBrewMethod.getBrewMethod().getName();
            this.description = profileBrewMethod.getBrewMethod()
                    .getDescription();
            this.recommendationNote = profileBrewMethod
                    .getRecommendationNote();
        }
    }
}

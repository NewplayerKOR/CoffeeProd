package com.back.coffeeprod.domain.recommendation.dto;

import com.back.coffeeprod.domain.coffeeprofile.dto.ProcessingMethodDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.recommendation.entity.MemberCoffeePreference;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class MemberCoffeePreferenceDto {

    // 회원 커피 취향을 입력 받음
    @Getter
    @NoArgsConstructor
    public static class Request {

        private RoastLevel roastLevel;

        private BeanType beanType;

        @Positive(message = "가공 방식 ID는 양수여야 합니다.")
        private Long processingMethodId;

        private boolean decaf;

        @Min(value = 1, message = "선호 산미 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "선호 산미 점수는 5 이하여야 합니다.")
        private Short preferredAcidity;

        @Min(value = 1, message = "선호 바디 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "선호 바디 점수는 5 이하여야 합니다.")
        private Short preferredBody;

        @Min(value = 1, message = "선호 단맛 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "선호 단맛 점수는 5 이하여야 합니다.")
        private Short preferredSweetness;

        @Min(value = 1, message = "선호 향 점수는 1 이상이어야 합니다.")
        @Max(value = 5, message = "선호 향 점수는 5 이하여야 합니다.")
        private Short preferredAroma;
    }

    // 회원 커피 취향을 반영함
    @Getter
    public static class Response {

        private final Long id;
        private final ProcessingMethodDto.Response processingMethod;
        private final BeanType beanType;
        private final RoastLevel roastLevel;
        private final Boolean decaf;
        private final Short preferredAcidity;
        private final Short preferredBody;
        private final Short preferredSweetness;
        private final Short preferredAroma;

        public Response(MemberCoffeePreference preference) {
            this.id = preference.getId();
            this.processingMethod = preference.getProcessingMethod() == null
                    ? null
                    : new ProcessingMethodDto.Response(
                    preference.getProcessingMethod()
            );
            this.beanType = preference.getBeanType();
            this.roastLevel = preference.getRoastLevel();
            this.decaf = preference.getDecaf();
            this.preferredAcidity = preference.getPreferredAcidity();
            this.preferredBody = preference.getPreferredBody();
            this.preferredSweetness = preference.getPreferredSweetness();
            this.preferredAroma = preference.getPreferredAroma();
        }
    }
}

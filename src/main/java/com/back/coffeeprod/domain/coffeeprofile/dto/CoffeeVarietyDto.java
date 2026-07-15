package com.back.coffeeprod.domain.coffeeprofile.dto;

import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeVariety;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class CoffeeVarietyDto {

    @Getter
    @NoArgsConstructor
    public static class CreateRequest {

        @NotBlank(message = "커피 품종 코드는 필수입니다.")
        @Size(max = 50, message = "커피 품종 코드는 50자 이하여야 합니다.")
        @Pattern(
                regexp = "^[A-Z][A-Z0-9_]*$",
                message = "커피 품종 코드는 영문 대문자, 숫자, 밑줄만 사용할 수 있습니다."
        )
        private String code;

        @NotBlank(message = "커피 품종명은 필수입니다.")
        @Size(max = 100, message = "커피 품종명은 100자 이하여야 합니다.")
        private String name;

        private String description;
    }

    @Getter
    @NoArgsConstructor
    public static class UpdateRequest {

        @NotBlank(message = "커피 품종명은 필수입니다.")
        @Size(max = 100, message = "커피 품종명은 100자 이하여야 합니다.")
        private String name;

        private String description;
    }

    @Getter
    public static class Response {
        private final Long id;
        private final String code;
        private final String name;
        private final String description;

        public Response(CoffeeVariety coffeeVariety) {
            this.id = coffeeVariety.getId();
            this.code = coffeeVariety.getCode();
            this.name = coffeeVariety.getName();
            this.description = coffeeVariety.getDescription();
        }
    }
}

package com.back.coffeeprod.domain.coffeeprofile.dto;

import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class ProcessingMethodDto {

    // 요청
    @Getter
    @NoArgsConstructor
    public static class CreateRequest {

        @NotBlank(message = "가공 방식 코드는 필수입니다.")
        @Size(max = 50, message = "가공 방식 코드는 50자 이하여야 합니다.")
        @Pattern(
                regexp = "^[A-Z][A-Z0-9_]*$",
                message = "가공 방식 코드는 영문 대문자와 밑줄만 사용할 수 있습니다."
        )
        private String code;

        @NotBlank(message = "가공 방식명은 필수입니다.")
        @Size(max = 100, message = "가공 방식명은 100자 이하여야 합니다.")
        private String name;

        private String description;
    }

    // 수정 요청
    @Getter
    @NoArgsConstructor
    public static class UpdateRequest {

        @NotBlank(message = "가공 방식명은 필수입니다.")
        @Size(max = 100, message = "가공 방식명은 100자 이하여야 합니다.")
        private String name;

        private String description;
    }

    // 응답
    @Getter
    public static class Response {
        private final Long id;
        private final String code;
        private final String name;
        private final String description;

        public Response(ProcessingMethod processingMethod) {
            this.id = processingMethod.getId();
            this.code = processingMethod.getCode();
            this.name = processingMethod.getName();
            this.description = processingMethod.getDescription();
        }
    }
}
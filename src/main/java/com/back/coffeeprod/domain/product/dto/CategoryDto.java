package com.back.coffeeprod.domain.product.dto;

import com.back.coffeeprod.domain.product.entity.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class CategoryDto {

    // 카테고리 등록/수정 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class Request {

        @NotBlank(message = "카테고리명은 필수입니다.")
        @Size(max = 50, message = "카테고리명은 50자 이하로 입력해야 합니다.")
        private String name;
    }

    // 카테고리 응답 DTO
    @Getter
    public static class Response {
        private final Long id;
        private final String name;

        public Response(Category category) {
            this.id = category.getId();
            this.name = category.getName();
        }
    }
}

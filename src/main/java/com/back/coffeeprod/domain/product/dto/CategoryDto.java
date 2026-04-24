package com.back.coffeeprod.domain.product.dto;

import com.back.coffeeprod.domain.product.entity.Category;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class CategoryDto {

    // 카테고리 등록/수정 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class Request {
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

package com.back.coffeeprod.domain.product.controller;

import com.back.coffeeprod.domain.product.dto.CategoryDto;
import com.back.coffeeprod.domain.product.service.CategoryService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Category", description = "카테고리 조회 API")
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation
            (
                    summary = "카테고리 목록 조회",
                    description = "등록된 전체 카테고리 목록을 반환합니다. 인증 불필요."
            )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ResponseEntity<CommonResponse<List<CategoryDto.Response>>> getAllCategories() {
        List<CategoryDto.Response> response = categoryService.getAllCategories();
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    @Operation(
            summary = "카테고리 상세 조회",
            description = "카테고리 ID로 단건 정보를 조회합니다. 인증 불필요."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @GetMapping("/{categoryId}")
    public ResponseEntity<CommonResponse<CategoryDto.Response>> getCategory(
            @Parameter(description = "조회할 카테고리 ID", required = true)
            @PathVariable Long categoryId) {

        CategoryDto.Response response = categoryService.getCategory(categoryId);
        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

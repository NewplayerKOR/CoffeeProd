package com.back.coffeeprod.domain.product.controller;

import com.back.coffeeprod.domain.product.dto.CategoryDto;
import com.back.coffeeprod.domain.product.service.CategoryService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin-Category", description = "관리자 카테고리 관리 API")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    // 카테고리 등록
    @Operation(
            summary = "카테고리 등록",
            description = "관리자가 신규 상품 카테고리를 등록합니다. 카테고리명은 중복될 수 없습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "카테고리 등록 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "409", description = "카테고리명 중복")
    })
    @PostMapping
    public ResponseEntity<CommonResponse<CategoryDto.Response>> createCategory(
            @Valid @RequestBody CategoryDto.Request request) {

        CategoryDto.Response response = categoryService.createCategory(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.success(response));
    }

    // 카테고리 수정
    @Operation(
            summary = "카테고리 수정",
            description = "관리자가 기존 카테고리명을 수정합니다. 자기 자신을 제외한 다른 카테고리명과 중복될 수 없습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "카테고리 수정 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "카테고리명 중복")
    })
    @PutMapping("/{categoryId}")
    public ResponseEntity<CommonResponse<CategoryDto.Response>> updateCategory(
            @Parameter(description = "수정할 카테고리 ID", required = true)
            @PathVariable Long categoryId,
            @Valid @RequestBody CategoryDto.Request request) {

        CategoryDto.Response response = categoryService.updateCategory(categoryId, request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

package com.back.coffeeprod.domain.product.controller;

import com.back.coffeeprod.domain.product.dto.ProductDto;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.product.service.ProductService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Product", description = "상품 조회 API")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(
            summary = "상품 목록 조회",
            description = """
                    판매 중인 상품 목록을 조회합니다.
                    - 카테고리, 커피 프로필, 로스팅강도, 키워드로 필터링 가능
                    - 정렬: price, asc / price, desc / createdAt, desc (기본값)
                    - 페이지네이션: page(0부터 시작), size(기본 10)
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ResponseEntity<CommonResponse<Page<ProductDto.SummaryResponse>>> getProducts(

            @Parameter(description = "카테고리 ID (선택)")
            @RequestParam(required = false) Long categoryId,

            @Parameter(description = "커피 프로필 ID (선택)")
            @RequestParam(required = false) Long coffeeProfileId,

            @Parameter(description = "로스팅 강도 (LIGHT | MEDIUM | DARK, 선택)")
            @RequestParam(required = false) RoastLevel roastLevel,

            @Parameter(description = "상품명 검색 키워드 (선택)")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "페이지네이션 및 정렬 정보")
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        Page<ProductDto.SummaryResponse> response = productService.getProducts(
                categoryId,
                coffeeProfileId,
                roastLevel,
                keyword,
                pageable
        );

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    @Operation(
            summary = "상품 상세 조회",
            description = "상품 ID로 상세 정보를 조회합니다. HIDDEN 상태 상품은 404를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @GetMapping("/{productId}")
    public ResponseEntity<CommonResponse<ProductDto.DetailResponse>> getProduct(
            @Parameter(description = "조회할 상품 ID", required = true)
            @PathVariable Long productId) {

        ProductDto.DetailResponse response = productService.getProduct(productId);
        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

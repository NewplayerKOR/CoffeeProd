package com.back.coffeeprod.domain.product.controller;

import com.back.coffeeprod.domain.product.dto.ProductDto;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.service.ProductService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.lettuce.core.dynamic.annotation.Param;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin-Product", description = "관리자 상품 관리 API")
@SecurityRequirement(name = "jwtAuth")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;

    // 상품 등록
    @Operation(
            summary = "상품 등록",
            description = "관리자가 신규 상품을 등록합니다. 등록된 상품의 기본 상태는 ON_SALE입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "상품 등록 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PostMapping
    public ResponseEntity<CommonResponse<ProductDto.DetailResponse>> createProduct(
            @RequestBody ProductDto.Request request) {

        ProductDto.DetailResponse response = productService.createProduct(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.success(response));
    }

    // 상품 전체 수정
    @Operation(
            summary = "상품 전체 수정",
            description = "관리자가 상품의 카테고리, 상품명, 가격, 재고, 로스팅 단계, 설명, 이미지를 수정합니다."
    )

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상품 수정 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "404", description = "상품 또는 카테고리를 찾을 수 없음")
    })
    @PutMapping
    public ResponseEntity<CommonResponse<ProductDto.DetailResponse>> updateProduct(
            @Parameter(description = "수정할 상품 ID", required = true)
            @PathVariable Long productId,
            @RequestBody ProductDto.Request request) {

        ProductDto.DetailResponse response = productService.updateProduct(productId, request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 상품 상태 변경
    @Operation(
            summary = "상품 상태 변경",
            description = "관리자가 상품 상태를 변경합니다. ON_SALE, SOLD_OUT, HIDDEN 값을 사용할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상품 상태 변경 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @PatchMapping("/{productId}/status")
    public ResponseEntity<CommonResponse<ProductDto.DetailResponse>> updateProductStatus(
            @Parameter(description = "상태를 변경할 상품 ID", required = true)
            @PathVariable Long productId,
            @RequestBody ProductDto.StatusRequest request) {

        ProductDto.DetailResponse response = productService.updateProductStatus(productId, request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 상품 재고 추가
    @Operation(
            summary = "상품 재고 추가",
            description = "관리자가 입고된 상품 수량을 기존 재고에 추가하며 주문 재고 차감과 별도 흐름입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상품 재고 추가 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @PatchMapping("/{productId}/stock")
    public ResponseEntity<CommonResponse<ProductDto.DetailResponse>> addStock(
            @Parameter(description = "재고를 추가할 상품 ID", required = true)
            @PathVariable Long productId,
            @RequestBody ProductDto.StockRequest request) {

        ProductDto.DetailResponse response = productService.addStock(productId, request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

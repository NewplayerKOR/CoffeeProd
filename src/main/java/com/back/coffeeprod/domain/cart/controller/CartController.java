package com.back.coffeeprod.domain.cart.controller;

import com.back.coffeeprod.domain.cart.dto.CartDto;
import com.back.coffeeprod.domain.cart.service.CartService;
import com.back.coffeeprod.global.common.CommonResponse;
import com.back.coffeeprod.global.security.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Cart", description = "장바구니 API")
@SecurityRequirement(name = "jwtAuth")
@RestController
@RequestMapping("/api/v1/carts")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    // 장바구니 조회
    @Operation(summary = "장바구니 조회",
            description = """
                    로그인한 회원의 장바구니를 조회합니다.
                    - 장바구니가 없으면 자동으로 생성 후 반환
                    전체 합계 금액 및 수량 포함
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @GetMapping
    public ResponseEntity<CommonResponse<CartDto.CartResponse>> getCart(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(cartService.getCart(memberId)));
    }

    // 장바구니 담기
    @Operation(summary = "장바구니 담기",
            description = """
                    상품을 장바구니에 추가합니다.
                    - 동일 상품 + 동일 분쇄 옵션이 이미 있으면 수량만 추가
                    - 판매 중(ON_SALE)인 상품만 담기 가능
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "담기 성공"),
            @ApiResponse(responseCode = "400", description = "판매 중이 아닌 상품"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @PostMapping("/items")
    public ResponseEntity<CommonResponse<CartDto.CartResponse>> addItem(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody CartDto.AddRequest request) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(cartService.addItem(memberId, request)));
    }

    // 수량 / 옵션 변경
    @Operation(summary = "장바구니 수량/옵션 변경",
            description = """
                    장바구니 상품의 수량 또는 분쇄 옵션을 변경합니다.
                    - 수량을 0이하로 변경하면 해당 상품이 삭제됩니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경 성공"),
            @ApiResponse(responseCode = "403", description = "본인 장바구니 아님"),
            @ApiResponse(responseCode = "404", description = "장바구니 상품을 찾을 수 없음")
    })
    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<CommonResponse<CartDto.CartResponse>> updateItem(
            @AuthenticationPrincipal CustomUserDetails userDetails,

            @Parameter(description = "변경할 장바구니 상품 ID", required = true)
            @PathVariable("cartItemId") Long cartItemId,

            @Valid @RequestBody CartDto.UpdateRequest request) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(cartService.updateItem(memberId, cartItemId, request)));
    }

    // 특정 상품 삭제
    @Operation(summary = "장바구니 특정 상품 삭제",
            description = "장바구니에서 특정 상품을 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = "본인 장바구니 아님"),
            @ApiResponse(responseCode = "404", description = "장바구니 상품을 찾을 수 없음")
    })
    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<CommonResponse<CartDto.CartResponse>> deleteItem(
            @AuthenticationPrincipal CustomUserDetails userDetails,

            @Parameter(description = "삭제할 장바구니 상품 ID", required = true)
            @PathVariable("cartItemId") Long cartItemId) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(cartService.deleteItem(memberId, cartItemId)));
    }

    // 장바구니 전체 비우기
    @Operation(summary = "장바구니 전체 비우기",
            description = "장바구니의 모든 상품을 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비우기 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @DeleteMapping
    public ResponseEntity<CommonResponse<CartDto.CartResponse>> clearCart(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Long memberId = userDetails.getMember().getId();
        cartService.clearCart(memberId);
        return ResponseEntity.ok(CommonResponse.success(200, "장바구니를 비웠습니다."));
    }
}

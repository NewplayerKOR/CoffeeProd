package com.back.coffeeprod.domain.order.controller;

import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.service.OrderService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Order", description = "주문 API")
@SecurityRequirement(name = "jwtAuth")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // 주문서 임시 생성 (결제 직전)
    @Operation(
            summary = "주문서 임시 생성",
            description = """
                    장바구니 상품으로 주문서를 생성합니다. (결제 직전 단계)
                    - 장바구니가 비어있으면 400 반환
                    - 재고 부족 시 400 반환
                    - 주문 생성 성공 시 장바구니 자동으로 비워짐
                    - 생성된 주문은 PENDING 상태 (결제 완료)
                    - 반환된 orderId와 totalPrice로 결제 요청
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "주문서 생성 성공"),
            @ApiResponse(responseCode = "400", description = "빈 장바구니 / 재고 부족 / 마일리지 초과"),
            @ApiResponse(responseCode = "404", description = "배송지를 찾을 수 없음")
    })
    @PostMapping
    public ResponseEntity<CommonResponse<OrderDto.DetailResponse>> createOrder(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody OrderDto.CreateRequest request) {

        Long memberId = userDetails.getMember().getId();
        OrderDto.DetailResponse response = orderService.createOrder(memberId, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(response));
    }

    // 내 주문 목록 조회
    @Operation(
            summary = "내 주문 목록 조회",
            description = """
                    로그인한 회원의 주문 목록을 최신순으로 반환합니다.
                    - 페이지네이션 지원 (기본: 10건)
                    - 각 주문의 대표 상품명과 총 금액 포함
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ResponseEntity<CommonResponse<Page<OrderDto.SummaryResponse>>> getMyOrder(
            @AuthenticationPrincipal CustomUserDetails userDetails,

            @Parameter(description = "페이지네이션 정보")
            @PageableDefault(size = 10, sort = "orderDate",
                    direction = Sort.Direction.DESC) Pageable pageable) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(orderService.getMyOrders(memberId, pageable)));
    }

    // 주문 상세 조회
    @Operation(
            summary = "주문 상세 조회",
            description = "주문 ID로 상세 정보를 조회 합니다. 본인 주문만 조회 가능합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "본인 주문 아님")
    })
    @GetMapping("/{orderId}")
    public ResponseEntity<CommonResponse<OrderDto.DetailResponse>> getOrder(
            @AuthenticationPrincipal CustomUserDetails userDetails,

            @Parameter(description = "조회할 주문 ID", required = true)
            @PathVariable("orderId") Long orderId) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(orderService.getOrder(memberId, orderId)));
    }

    // 주문 취소
    @Operation(
            summary = "주문 취소",
            description = """
                    주문을 취소합니다.
                    - PENDING, PAID 상태만 취소 가능
                    - SHIPPED, DELIVERED 상태는 취소 불가
                    - 취소 시 재고 및 마일리지 자동 복구
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 성공"),
            @ApiResponse(responseCode = "400", description = "취소 불가 상태"),
            @ApiResponse(responseCode = "403", description = "본인 주문 아님"),
            @ApiResponse(responseCode = "404", description = "주문을 찾을 수 없음")
    })
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CommonResponse<OrderDto.DetailResponse>> cancelOrder(
            @AuthenticationPrincipal CustomUserDetails userDetails,

            @Parameter(description = "취소할 주문 ID", required = true)
            @PathVariable("orderId") Long orderId) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(orderService.cancelOrder(memberId, orderId)));
    }
}
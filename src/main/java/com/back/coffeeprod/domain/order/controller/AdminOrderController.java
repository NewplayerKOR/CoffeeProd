package com.back.coffeeprod.domain.order.controller;

import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin-Order", description = "관리자 주문 관리 API")
@SecurityRequirement(name = "jwtAuth")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;
    private final com.back.coffeeprod.domain.payment.service.PaymentWorkflow paymentWorkflow;
    private final com.back.coffeeprod.domain.payment.service.PaymentOperationQueryService operationQuery;

    // 전체 주문 목록 조회
    @Operation(
            summary = "전체 주문 목록 조회",
            description = """
                    관리자가 전체 주문 목록을 페이지 단위로 조회 합니다.
                    최신 주문 순으로 조회하며, 주문자 정보와 주문 요약 정보를 함께 반환합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "전체 주문 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음")
    })
    @GetMapping
    public ResponseEntity<CommonResponse<Page<OrderDto.AdminSummaryResponse>>> getAllOrders(
            @Parameter(description = "페이지 및 정렬 정보")
            @PageableDefault(size = 20, sort = "orderDate", direction = Sort.Direction.DESC)
            Pageable pageable) {

        Page<OrderDto.AdminSummaryResponse> response = orderService.getAllOrders(pageable);

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 주문 상태 변경 및 운송장 등록
    @Operation(
            summary = "주문 상태 변경 및 운송장 등록",
            description = """
                    관리자가 주문 상태를 변경합니다.
                    PAID 상태에서 SHIPPED로 변경할 때는 운송장 번호가 필수입니다.
                    
                    허용 상태 전이:
                    - PENDING -> CANCELED (승인 진행 중이면 결과 확인 후 취소)
                    - PAID -> SHIPPED, CANCELED
                    - SHIPPED -> DELIVERED
                    - DELIVERED, CANCELED -> 변경 불가
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "주문 상태 변경 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 주문 상태 전이 또는 운송장 번호 누락"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "404", description = "주문을 찾을 수 없음")
    })
    @PatchMapping("/{orderId}/status")
    public ResponseEntity<CommonResponse<OrderDto.DetailResponse>> updateOrderStatus(
            @Parameter(description = "상태를 변경할 주문 ID", required = true)
            @PathVariable Long orderId,
            @RequestBody OrderDto.StatusUpdateRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.back.coffeeprod.global.security.auth.CustomUserDetails userDetails) {

        if (request.getStatus() == com.back.coffeeprod.domain.order.entity.OrderStatus.CANCELED) {
            var result = paymentWorkflow.cancel(userDetails.getMember().getId(),orderId,true);
            return ResponseEntity.status(result.status()).body(CommonResponse.success(result.status(),result.order()));
        }
        OrderDto.DetailResponse response = orderService.updateOrderStatus(orderId, request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }
    @Operation(summary = "결제 작업 조회", description = "결제 키·PG 원문을 제외한 작업 상태를 조회합니다.")
    @GetMapping("/payment-operations")
    public ResponseEntity<CommonResponse<Page<com.back.coffeeprod.domain.payment.dto.PaymentOperationDto>>> operations(
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) com.back.coffeeprod.domain.payment.entity.PaymentOperation.Status status,
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(CommonResponse.success(operationQuery.list(orderId,status,pageable)));
    }

    @Operation(summary = "결제 작업 재확인", description = "기존 작업을 PG 조회로 재확인합니다. 강제 완료·새 멱등키 발급은 지원하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "해결 완료"),
            @ApiResponse(responseCode = "202", description = "확인 예약 또는 진행 중"),
            @ApiResponse(responseCode = "409", description = "자동 판정 불가")})
    @PostMapping("/{orderId}/payment-reconcile")
    public ResponseEntity<CommonResponse<OrderDto.DetailResponse>> reconcile(@PathVariable Long orderId) {
        var result = paymentWorkflow.reconcile(orderId);
        return ResponseEntity.status(result.status()).body(CommonResponse.success(result.status(),result.order()));
    }
}

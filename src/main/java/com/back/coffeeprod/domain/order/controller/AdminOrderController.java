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
                    - PENDING -> PAID, CANCELED
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
            @RequestBody OrderDto.StatusUpdateRequest request) {

        OrderDto.DetailResponse response = orderService.updateOrderStatus(orderId, request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

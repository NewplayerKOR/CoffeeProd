package com.back.coffeeprod.domain.payment.controller;

import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.back.coffeeprod.domain.payment.service.PaymentService;
import com.back.coffeeprod.global.common.CommonResponse;
import com.back.coffeeprod.global.security.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment", description = "결제 API")
@SecurityRequirement(name = "jwtAuth")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // 결제 승인 및 검증
    @Operation(
            summary = "결제 승인 및 검증",
            description = """
                    토스페이먼트 결제 승인을 처리합니다 (가장 중요 API)
                    
                    [클라이언트 호출 순서]
                    1. POST /api/v1/orders -> 주문 생성 (orderId, totalPrice 수신)
                    2. 토스 SDK로 결제 UI 실행 -> 사용자 결제 완료
                    3. 토스에서 paymentKey 수신
                    4. POST /api/v1/payments/confirm 호출 (이 API)
                    
                    [서버 처리 순서]
                    1. 주문 상태 검증 (PENDING만 허용)
                    2. 금액 위변조 검증 (DB 금액 vs 요청 금액)
                    3. 토스 결제 승인 API 호출
                    4. 주문 상태 PAID로 변경 + Payment 레코드 저장
    
                    [실패 시]
                    - 금액 위변조 감지: 주문 취소 + 재고 복구 후 400 반환
                    - 결제 승인 실패: 주문 취소 + 재고 복구 후 400 반환
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "결제 승인 성공"),
            @ApiResponse(responseCode = "400", description = "금액 불일치 / 결제 승인 실패 / 잘못된 주문 상태"),
            @ApiResponse(responseCode = "404", description = "주문을 찾을 수 없음")
    })
    @PostMapping("/confirm")
    public ResponseEntity<CommonResponse<PaymentDto.Response>> confirmPayment(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody PaymentDto.ConfirmRequest request) {

        Long memberId = userDetails.getMember().getId();
        PaymentDto.Response response = paymentService.confirmPayment(memberId, request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

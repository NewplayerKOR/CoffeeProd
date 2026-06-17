package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.domain.member.entity.MemberStatus;
import com.back.coffeeprod.domain.order.entity.OrderStatus;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.back.coffeeprod.domain.payment.dto.TossPaymentResponse;
import com.back.coffeeprod.domain.payment.entity.Payment;
import com.back.coffeeprod.domain.payment.entity.PaymentStatus;
import com.back.coffeeprod.domain.payment.gateway.PaymentGateway;
import com.back.coffeeprod.domain.payment.policy.MileagePolicyProperties;
import com.back.coffeeprod.domain.payment.repository.PaymentRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderService orderService;
    private final PaymentGateway paymentGateway;    // 환경에 따라 Fake or Toss 사용
    private final MileagePolicyProperties mileagePolicyProperties;


    // 결제 승인 검증
    @Transactional
    public PaymentDto.Response confirmPayment(Long memberId, PaymentDto.ConfirmRequest request) {
        // 1. 주문 조회
        Orders orders = orderService.findOrderByTossOrderId(request.getTossOrderId());

        if (!orders.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.ORDER_ACCESS_DENIED);
        }

        if (orders.getMember().getStatus() == MemberStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.SUSPENDED_MEMBER);
        }

        // 2. 주문 상태 검증 - PENDING 상태만 가능
        // 결제된 주문, 취소된 주문에 대한 중복 결제 차단
        if (orders.getStatus() != OrderStatus.PENDING) {
            throw new CustomException(ErrorCode.INVALID_ORDER_STATUS);
        }

        // 3. 금액 위변조 검증
        if (orders.getTotalPrice() != request.getAmount()) {
            log.warn("[PaymentService] 금액 위변조 감지 - tossOrderId: {}, 기대금액: {}, 요청금액: {}",
                    request.getTossOrderId(), orders.getTotalPrice(), request.getAmount());

            // 위변조 감지 시 주문 취소 + 재고 복구 처리
            cancelOrderOnPaymentFailure(orders.getId());
            throw new CustomException(ErrorCode.INVALID_ORDER_AMOUNT);
        }

        // 4. 결제사 API 호출
        TossPaymentResponse tossResponse;
        try {
            tossResponse = paymentGateway.confirm(
                    request.getPaymentKey(),
                    request.getTossOrderId(),
                    request.getAmount()
            );
        } catch (CustomException e) {
            // 결제 실패 시 주문 취소 - 재고 복구
            log.error("[PaymentService] 결제 승인 실패 - tossOrderId: {}", request.getTossOrderId());
            cancelOrderOnPaymentFailure(orders.getId());
            throw e;
        }

        // 5. 결제사 응답 상태 검증
        // 토스 응답 'DONE' 아니면 결제 실패 처리
        if (tossResponse == null || !"DONE".equals(tossResponse.getStatus())) {
            log.error("[PaymentService] 결제 상태 비정상 - status: {}",
                    tossResponse != null ? tossResponse.getStatus() : null);
            cancelOrderOnPaymentFailure(orders.getId());
            throw new CustomException(ErrorCode.PAYMENT_FAILED);
        }

        // 6. 토스 승인 응답의 주문번호와 금액을 서버 주문 정보와 재검증
        if (!request.getTossOrderId().equals(tossResponse.getOrderId())) {
            log.warn("[PaymentService] 토스 주문번호 불일치 - request: {}, response: {}",
                    request.getTossOrderId(), tossResponse.getOrderId());
            cancelOrderOnPaymentFailure(orders.getId());
            throw new CustomException(ErrorCode.PAYMENT_FAILED);
        }

        if (orders.getTotalPrice() != tossResponse.getTotalAmount()) {
            log.warn("[PaymentService] 토스 승인 금액 불일치 - orderId: {}, expected: {}, actual: {}",
                    orders.getId(), orders.getTotalPrice(), tossResponse.getTotalAmount());
            cancelOrderOnPaymentFailure(orders.getId());
            throw new CustomException(ErrorCode.INVALID_ORDER_AMOUNT);
        }

        // 7. 적립 마일리지 계산
        int earnedMileage = mileagePolicyProperties.calculateRewardMileage(
                orders.getProductTotalPrice(),
                orders.getUsedMileage()
        );

        // 8. 주문 상태 PAID 변경 + 적립 마일리지 스냅샷 저장
        orders.markAsPaid(earnedMileage);

        // 9. 회원 마일리지 적립
        orders.getMember().addMileage(earnedMileage);

        // 10. Payment 레코드 저장
        Payment payment = Payment.builder()
                .orders(orders)
                .pgProvider("TOSSPAYMENTS")
                .paymentKey(request.getPaymentKey())
                .payMethod(tossResponse.getMethod())
                .status(PaymentStatus.SUCCESS)
                .build();

        Payment savedPayment = paymentRepository.save(payment);

        log.info("[PaymentService] 결제 완료 - tossOrderId: {}, paymentKey: {}",
                request.getTossOrderId(), payment.getPaymentKey());

        return new PaymentDto.Response(savedPayment);
    }


    // [내부] 결제 실패 시 주문 취소 + 재고 복구
    private void cancelOrderOnPaymentFailure(Long orderId) {
        orderService.cancelOrderForPaymentFailure(orderId);

        log.info("[PaymentService] 결제 실패로 인한 주문 취소 처리 - orderId: {}", orderId);
    }
}

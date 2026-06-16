package com.back.coffeeprod.domain.payment.gateway;

import com.back.coffeeprod.domain.payment.dto.TossPaymentResponse;

/**
 * 결제 게이트웨이 인터페이스
 * <p>
 * - TossPaymentGateway : 실제 토스 API 호출 (prod)
 * - FakePaymentGateway : 항상 성공 반환 (dev, test)
 * <p>
 * 분리 사유:
 * - 결제사 교체 시 구현체만 바꾸면 가능
 * - 테스트 시 실제 외부 API 호출 없이 FakeGateway로 대체 가능
 */
public interface PaymentGateway {

    TossPaymentResponse confirm(String paymentKey, String tossOrderId, int amount);
}

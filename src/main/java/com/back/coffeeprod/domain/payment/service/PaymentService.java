package com.back.coffeeprod.domain.payment.service;
import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.back.coffeeprod.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class PaymentService {
    private final PaymentWorkflow workflow;
    public PaymentWorkflow.Result confirmPaymentResult(Long memberId, PaymentDto.ConfirmRequest request) {
        return workflow.confirm(memberId,request);
    }
    public PaymentDto.Response confirmPayment(Long memberId, PaymentDto.ConfirmRequest request) {
        var result = workflow.confirm(memberId,request);
        if (result.status() != 200 || result.payment() == null) throw new CustomException(ErrorCode.PAYMENT_OPERATION_CONFLICT);
        return result.payment();
    }
}

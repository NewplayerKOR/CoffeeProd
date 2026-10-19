package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.domain.payment.dto.PaymentOperationDto;
import com.back.coffeeprod.domain.payment.entity.PaymentOperation;
import com.back.coffeeprod.domain.payment.repository.PaymentOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PaymentOperationQueryService {
    private final PaymentOperationRepository repository;
    @Transactional(readOnly = true)
    public Page<PaymentOperationDto> list(Long orderId, PaymentOperation.Status status, Pageable pageable) {
        return repository.search(orderId,status,pageable).map(PaymentOperationDto::new);
    }
}

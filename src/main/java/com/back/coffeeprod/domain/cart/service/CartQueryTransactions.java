package com.back.coffeeprod.domain.cart.service;

import com.back.coffeeprod.domain.cart.dto.CartDto;
import com.back.coffeeprod.domain.cart.entity.Cart;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// 별도 Bean의 프록시를 통해 조회와 최초 생성의 트랜잭션 경계를 분리한다.
@Service
@RequiredArgsConstructor
public class CartQueryTransactions {
    private final CartRepository cartRepository;
    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public Optional<CartDto.CartResponse> findCart(Long memberId) {
        return cartRepository.findByMemberIdWithItems(memberId).map(CartDto.CartResponse::new);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createCartIfAbsent(Long memberId) {
        var member = memberRepository.findLockedById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        // 잠금 대기 중 다른 조회·쓰기 요청이 생성했을 수 있으므로 다시 확인한다.
        if (cartRepository.findByMemberId(memberId).isEmpty()) {
            cartRepository.save(Cart.builder().member(member).build());
        }
    }
}

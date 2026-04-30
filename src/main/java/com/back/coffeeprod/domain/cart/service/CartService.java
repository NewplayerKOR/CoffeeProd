package com.back.coffeeprod.domain.cart.service;

import com.back.coffeeprod.domain.cart.dto.CartDto;
import com.back.coffeeprod.domain.cart.entity.Cart;
import com.back.coffeeprod.domain.cart.entity.CartItem;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.service.ProductService;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final MemberService memberService;
    private final ProductService productService;

    // 장바구니 조회
    public CartDto.CartResponse getCart(Long memberId) {
        // 장바구니 없으면 빈 바구니 생성
        Cart cart = getOrCreateCart(memberId);
        return new CartDto.CartResponse(cart);
    }

    // 장바구니 담기
    @Transactional
    public CartDto.CartResponse addItem(Long memberId, CartDto.AddRequest request) {
        Cart cart = getOrCreateCart(memberId);
        Product product = productService.findProductById(request.getProductId());

        // 판매중인 상품만 담기
        if (product.getStatus() != ProductStatus.ON_SALE) {
            throw new CustomException(ErrorCode.PRODUCT_NOT_ON_SALE);
        }

        // 동일 상품 + 동일 분쇄 옵션 중복 여부 확인
        cartItemRepository.findByCartIdAndProductIdAndGrindType(
                        cart.getId(), product.getId(), request.getGrindType())
                .ifPresentOrElse(
                        // 이미 존재 -> 수량 추가
                        existing -> existing.addQuantity(request.getQuantity()),
                        // 없으면 -> 새 CartItem생성
                        () -> {
                            CartItem newItem = CartItem.builder()
                                    .cart(cart)
                                    .product(product)
                                    .quantity(request.getQuantity())
                                    .grindType(request.getGrindType())
                                    .build();
                            cartItemRepository.save(newItem);
                        }
                );

        // 변경 후 최신 장바구니 다시 조회 후 반환
        Cart updatedCart = cartRepository.findByMemberIdWithItems(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.CART_NOT_FOUND));
        return new CartDto.CartResponse(updatedCart);
    }

    // 수량 / 옵션 변경
    @Transactional
    public CartDto.CartResponse updateItem(Long memberId, Long cartItemId, CartDto.UpdateRequest request) {

        Cart cart = getOrCreateCart(memberId);
        CartItem cartItem = findCartItemByIdAndCartId(cartItemId, cart.getId());

        // 수량 0 이하면 해당 아이템 삭제 처리
        if (request.getQuantity() <= 0) {
            cartItemRepository.delete(cartItem);
        } else {
            cartItem.update(request.getQuantity(), request.getGrindType());
        }

        Cart updatedCart = cartRepository.findByMemberIdWithItems(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.CART_NOT_FOUND));
        return new CartDto.CartResponse(updatedCart);
    }

    // 특정 상품 삭제
    @Transactional
    public CartDto.CartResponse deleteItem(Long memberId, Long cartItemId) {
        Cart cart = getOrCreateCart(memberId);
        CartItem cartItem = findCartItemByIdAndCartId(cartItemId, cart.getId());

        cartItemRepository.delete(cartItem);

        Cart updatedCart = cartRepository.findByMemberIdWithItems(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.CART_NOT_FOUND));
        return new CartDto.CartResponse(updatedCart);
    }

    // 장바구니 전체 비우기
    @Transactional
    public void clearCart(Long memberId) {
        Cart cart = getOrCreateCart(memberId);
        cart.clear();
    }

    // [내부 공용] 장바구니 조회 or 신규 생성
    @Transactional
    public Cart getOrCreateCart(Long memberId) {
        return cartRepository.findByMemberId(memberId)
                .orElseGet(() -> {
                    // 장바구니 없으면 자동 생성
                    Member member = memberService.findMemberById(memberId);
                    return cartRepository.save(Cart.builder()
                            .member(member)
                            .build());
                });
    }

    // [내부 공용] CartItem 조회 -> 본인 소유 검증
    private CartItem findCartItemByIdAndCartId(Long cartItemId, Long cartId) {
        CartItem cartItem = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new CustomException(ErrorCode.CART_ITEM_NOT_FOUND));

        // 다른 회원 장바구니 접근 차단 (IDOR 방어)
        if (!cartItem.getCart().getId().equals(cartId)) {
            throw new CustomException(ErrorCode.CART_ACCESS_DENIED);
        }

        return cartItem;
    }
}

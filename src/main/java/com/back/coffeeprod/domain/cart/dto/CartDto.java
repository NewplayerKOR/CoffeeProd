package com.back.coffeeprod.domain.cart.dto;

import com.back.coffeeprod.domain.cart.entity.Cart;
import com.back.coffeeprod.domain.cart.entity.CartItem;
import com.back.coffeeprod.domain.cart.entity.GrindType;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

public class CartDto {

    // 장바구니 담기 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class AddRequest {
        private Long productId;
        private int quantity;
        private GrindType grindType;
    }

    // 수량/옵션 변경 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class UpdateRequest {
        private int quantity;
        private GrindType grindType;
    }

    // 장바구니 상품 응답 DTO
    @Getter
    public static class CartItemResponse {
        private final Long cartItemId;
        private final Long productId;
        private final String productName;
        private final int price;
        private final int quantity;
        private final GrindType grindType;
        private final int totalPrice;
        private final String imageUrl;

        public CartItemResponse(CartItem cartItem) {
            this.cartItemId = cartItem.getId();
            this.productId = cartItem.getProduct().getId();
            this.productName = cartItem.getProduct().getName();
            this.price = cartItem.getProduct().getPrice();
            this.quantity = cartItem.getQuantity();
            this.grindType = cartItem.getGrindType();
            this.totalPrice = cartItem.getProduct().getPrice() * cartItem.getQuantity();
            this.imageUrl = cartItem.getProduct().getImageUrl();
        }
    }

    // 장바구니 전체 응답 DTO
    @Getter
    public static class CartResponse {
        private final List<CartItemResponse> items;
        private final int totalPrice;
        private final int totalQuantity;

        public CartResponse(Cart cart) {
            this.items = cart.getCartItems()
                    .stream()
                    .map(CartItemResponse::new)
                    .collect(Collectors.toList());

            // 전체 합계
            this.totalPrice = this.items.stream()
                    .mapToInt(CartItemResponse::getTotalPrice)
                    .sum();

            // 전체 수량 합계
            this.totalQuantity = this.items.stream()
                    .mapToInt(CartItemResponse::getQuantity)
                    .sum();
        }
    }
}

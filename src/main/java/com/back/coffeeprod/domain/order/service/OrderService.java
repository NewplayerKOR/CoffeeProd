package com.back.coffeeprod.domain.order.service;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.entity.Cart;
import com.back.coffeeprod.domain.cart.entity.CartItem;
import com.back.coffeeprod.domain.cart.service.CartService;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.entity.OrderItem;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final AddressRepository addressRepository;
    private final MemberService memberService;
    private final CartService cartService;

    // 주문서 임시 생성 (결제 직전)
    @Transactional
    public OrderDto.DetailResponse createOrder(Long memberId, OrderDto.CreateRequest request) {
        Member member = memberService.findMemberById(memberId);

        // 1. 장바구니 조회 및 빈 장바구니 검증
        Cart cart = cartService.getOrCreateCart(memberId);
        List<CartItem> cartItems = cart.getCartItems();

        if (cartItems.isEmpty()) {
            throw new CustomException(ErrorCode.EMTY_CART);
        }

        // 2. 배송지 조회 및 본인 소유 검증
        Address address = addressRepository.findById(request.getAddressId())
                .orElseThrow(() -> new CustomException(ErrorCode.ADDRESS_NOT_FOUND));

        if (!address.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.ADDRESS_ACCESS_DENIED);
        }

        // 3. 배송지 스냅샷 문자열 생성
        // -> 주문 후 배송지 수정/삭제되어도 주문 당시 주소 보존
        String deliverySnapshot = String.format("[%s] %s %s (%s)",
                address.getRecipient(),
                address.getAddressLine1(),
                address.getAddressLine2() != null ? address.getAddressLine2() : "",
                address.getPhone());

        // 4. 마일리지 검증
        int usedMileage = request.getUsedMileage();
        if (usedMileage > member.getMileage()) {
            throw new CustomException(ErrorCode.INVALID_MILEAGE);
        }

        // 5. 재고 차감 + 주문 금액 계산
        // -> 각 상품의 재고를 차감하고 총 금액 합산
        int totalPrice = 0;
        for (CartItem cartItem : cartItems) {
            // 재고 부족시 CustomException(OUT_OF_STOCK) 발생
            cartItem.getProduct().decreaseStock(cartItem.getQuantity());
            totalPrice += cartItem.getProduct().getPrice() * cartItem.getQuantity();
        }

        // 6. 마일리지 차감 적용
        totalPrice = Math.max(0, totalPrice - usedMileage);

        // 7. 주문 생성
        Orders orders = Orders.builder()
                .member(member)
                .totalPrice(totalPrice)
                .usedMileage(usedMileage)
                .deliveryAddress(deliverySnapshot)
                .build();

        orderRepository.save(orders);

        // 8. 주문 상품 생성 (가격 스냅샷 저장)
        for (CartItem cartItem : cartItems) {
            OrderItem orderItem = OrderItem.builder()
                    .orders(orders)
                    .product(cartItem.getProduct())
                    .orderPrice(cartItem.getProduct().getPrice())   // 현재 가격 스냅샷
                    .quantity(cartItem.getQuantity())
                    .grindType(cartItem.getGrindType())
                    .build();
            orders.getOrderItems().add(orderItem);
        }

        // 9. 장바구니 비우기
        cart.clear();

        return new OrderDto.DetailResponse(orders);
    }

    // 내 주문 목록 조회
    public Page<OrderDto.SummaryResponse> getMyOrders(Long memberId, Pageable pageable) {
        return orderRepository.findByMemberIdWithItems(memberId, pageable)
                .map(OrderDto.SummaryResponse::new);
    }

    // 주문 상세 조회
    public OrderDto.DetailResponse getOrderDetail(Long memberId, Long orderId) {
        Orders orders = findOrderByIdWithItems(orderId);

        // 본인 주문만 조회 가능
        if (!orders.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.ORDER_ACCESS_DENIED);
        }

        return new OrderDto.DetailResponse(orders);
    }

    // 주문 취소
    @Transactional
    public OrderDto.DetailResponse cancelOrder(Long memberId, Long orderId) {
        Orders orders = findOrderByIdWithItems(orderId);

        // 본인 주문만 취소 가능
        if (!orders.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.ORDER_ACCESS_DENIED);
        }

        // 취소 상태 검증(PENDING, PAID만 가능)
        // -> 내부에서 불가능한 상태면 IllegalStateException 발생
        try {
            orders.cancel();
        } catch (IllegalStateException e) {
            throw new CustomException(ErrorCode.INVALID_ORDER_STATUS);
        }

        // 재고 복구 - 취소된 주문의 각 상품 재고를 원복
        orders.getOrderItems()
                .forEach(item -> item.getProduct().addStock(item.getQuantity()));

        // 마일리지 복구
        if (orders.getUsedMileage() > 0) {
            orders.getMember().addMileage(orders.getUsedMileage());
        }

        return new OrderDto.DetailResponse(orders);
    }

    // [내부 공용] 주문 결제 완료 처리
    @Transactional
    public void markAsPaid(Long orderId) {
        Orders orders = findOrderById(orderId);
        orders.markAsPaid();
    }

    // [내부 공용] 주문 단건 조회 (OrderItem 포함)
    public Orders findOrderByIdWithItems(Long orderId) {
        return orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));
    }

    // [내부 공용] 주문 단건 조회
    public Orders findOrderById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));
    }
}

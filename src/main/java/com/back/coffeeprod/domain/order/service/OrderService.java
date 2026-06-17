package com.back.coffeeprod.domain.order.service;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.entity.Cart;
import com.back.coffeeprod.domain.cart.entity.CartItem;
import com.back.coffeeprod.domain.cart.service.CartService;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.entity.OrderItem;
import com.back.coffeeprod.domain.order.entity.OrderStatus;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.policy.DeliveryPolicyProperties;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final AddressRepository addressRepository;
    private final MemberService memberService;
    private final CartService cartService;
    private final ProductRepository productRepository;
    private final DeliveryPolicyProperties deliveryPolicyProperties;

    // 주문서 임시 생성 (결제 직전)
    @Transactional
    public OrderDto.DetailResponse createOrder(Long memberId, OrderDto.CreateRequest request) {
        Member member = memberService.findMemberById(memberId);

        // 정지회원 주문 차단
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.SUSPENDED_MEMBER);
        }

        // 1. 장바구니 조회 및 빈 장바구니 검증
        Cart cart = cartService.getOrCreateCart(memberId);
        List<CartItem> cartItems = cart.getCartItems();

        if (cartItems.isEmpty()) {
            throw new CustomException(ErrorCode.EMPTY_CART);
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
        if (usedMileage < 0 || usedMileage > member.getMileage()) {
            throw new CustomException(ErrorCode.INVALID_MILEAGE);
        }

        // 5. 주문 상품 금액 계산
        int orderProductTotalPrice = 0;
        for (CartItem cartItem : cartItems) {
            orderProductTotalPrice += cartItem.getProduct().getPrice() * cartItem.getQuantity();
        }

        if (usedMileage > orderProductTotalPrice) {
            throw new CustomException(ErrorCode.INVALID_MILEAGE);
        }

        // 배송비는 서버 정책으로 계산한다.
        int deliveryFee = deliveryPolicyProperties.calculateDeliveryFee(orderProductTotalPrice);

        // 6. 마일리지 차감
        member.useMileage(usedMileage);

        // 7. 재고 차감
        for (CartItem cartItem : cartItems) {
            int updatedRows = productRepository.decreaseStockIfEnough(
                    cartItem.getProduct().getId(),
                    cartItem.getQuantity()
            );

            if (updatedRows == 0) {
                throw new CustomException(ErrorCode.OUT_OF_STOCK);
            }
        }

        // 8. 최종 결제 금액 계산
        int totalPrice = orderProductTotalPrice - usedMileage + deliveryFee;

        // 9. 주문 생성
        Orders orders = Orders.builder()
                .member(member)
                .tossOrderId(createTossOrderId())
                .productTotalPrice(orderProductTotalPrice)
                .deliveryFee(deliveryFee)
                .totalPrice(totalPrice)
                .usedMileage(usedMileage)
                .deliveryAddress(deliverySnapshot)
                .build();

        orderRepository.save(orders);

        // 10. 주문 상품 생성 (가격 스냅샷 저장)
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

        // 11. 장바구니 비우기
        cart.clear();

        return new OrderDto.DetailResponse(orders);
    }

    // [관리자] 전체 주문 목록 조회
    public Page<OrderDto.AdminSummaryResponse> getAllOrders(Pageable pageable) {
        Page<Long> orderIdPage = orderRepository.findAllIds(pageable);

        if (orderIdPage.isEmpty()) {
            return Page.empty(pageable);
        }

        // 주문 ID 페이지 조회 후 해당 주문들의 주문상품/상품 정보를 한 번에 조회 한다.
        List<Orders> orders = orderRepository.findAllWithItemsByIdIn(orderIdPage.getContent());

        List<OrderDto.AdminSummaryResponse> responses = orders.stream()
                .map(OrderDto.AdminSummaryResponse::new)
                .toList();

        return new PageImpl<>(responses, pageable, orderIdPage.getTotalElements());
    }

    // 내 주문 목록 조회
    public Page<OrderDto.SummaryResponse> getMyOrders(Long memberId, Pageable pageable) {
        Page<Long> orderIdPage = orderRepository.findIdsByMemberId(memberId, pageable);

        if (orderIdPage.isEmpty()) {
            return Page.empty(pageable);
        }

        List<Orders> orders = orderRepository.findAllWithItemsByIdIn(orderIdPage.getContent());

        List<OrderDto.SummaryResponse> responses = orders.stream()
                .map(OrderDto.SummaryResponse::new)
                .toList();

        return new PageImpl<>(responses, pageable, orderIdPage.getTotalElements());
    }

    // 주문 상세 조회
    public OrderDto.DetailResponse getOrder(Long memberId, Long orderId) {
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

        cancelAndRestore(orders);

        return new OrderDto.DetailResponse(orders);
    }

    // 결제 실패 보상 메서드
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelOrderForPaymentFailure(Long orderId) {
        Orders orders = findOrderByIdWithItems(orderId);
        cancelAndRestore(orders);
    }

    // [관리자] 관리자 주문 상태 변경
    @Transactional
    public OrderDto.DetailResponse updateOrderStatus(
            Long orderId,
            OrderDto.StatusUpdateRequest request
    ) {
        Orders orders = findOrderByIdWithItems(orderId);
        OrderStatus nextStatus = request.getStatus();

        validateOrderStatusTransition(orders, nextStatus, request.getTrackingNo());

        orders.updateStatus(nextStatus, request.getTrackingNo());

        return new OrderDto.DetailResponse(orders);
    }

    // 주문 상태 전이 검증
    private void validateOrderStatusTransition(
            Orders orders,
            OrderStatus nextStatus,
            String trackingNo
    ) {
        if (nextStatus == null) {
            throw new CustomException(ErrorCode.INVALID_ORDER_STATUS);
        }

        OrderStatus currentStatus = orders.getStatus();

        if (currentStatus == OrderStatus.DELIVERED || currentStatus == OrderStatus.CANCELED) {
            throw new CustomException(ErrorCode.INVALID_ORDER_STATUS);
        }

        if (nextStatus == OrderStatus.SHIPPED && (trackingNo == null || trackingNo.isBlank())) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }

        boolean allowed = switch (currentStatus) {
            case PENDING -> nextStatus == OrderStatus.PAID
                    || nextStatus == OrderStatus.CANCELED;
            case PAID -> nextStatus == OrderStatus.SHIPPED
                    || nextStatus == OrderStatus.CANCELED;
            case SHIPPED -> nextStatus == OrderStatus.DELIVERED;
            case DELIVERED, CANCELED -> false;
        };

        if (!allowed) {
            throw new CustomException(ErrorCode.INVALID_ORDER_STATUS);
        }
    }

    // 보상 로직
    private void cancelAndRestore(Orders orders) {
        if (orders.getStatus() == OrderStatus.CANCELED) {
            return;
        }

        try {
            orders.cancel();
        } catch (IllegalStateException e) {
            throw new CustomException(ErrorCode.INVALID_ORDER_STATUS);
        }

        orders.getOrderItems()
                .forEach(item -> item.getProduct().addStock(item.getQuantity()));

        if (orders.getUsedMileage() > 0) {
            orders.getMember().addMileage(orders.getUsedMileage());
        }

        // 결제 완료 후 적립된 마일리지는 주문 취소 시 회수
        if (orders.getEarnedMileage() > 0) {
            orders.getMember().useMileage(orders.getEarnedMileage());
        }
    }

    // [내부 공용] 주문 결제 완료 처리
    @Transactional
    public void markAsPaid(Long orderId) {
        Orders orders = findOrderById(orderId);
        orders.markAsPaid(0);
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

    // [내부 공용] 토스 주문번호로 주문 단건 조회
    public Orders findOrderByTossOrderId(String tossOrderId) {
        return orderRepository.findByTossOrderId(tossOrderId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));
    }

    // 토스페이먼츠 결제 요청용 주문번호 생성
    private String createTossOrderId() {
        return "COFFEE-" + UUID.randomUUID().toString().replace("-", "");
    }
}

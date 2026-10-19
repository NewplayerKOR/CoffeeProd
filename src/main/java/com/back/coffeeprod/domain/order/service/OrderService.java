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
    private final com.back.coffeeprod.domain.payment.service.PaymentWorkflow paymentWorkflow;
    private final com.back.coffeeprod.domain.payment.service.PaymentTransactions paymentTransactions;
    private final com.back.coffeeprod.domain.member.repository.MemberRepository memberRepository;
    private final com.back.coffeeprod.domain.cart.repository.CartRepository cartRepository;
    private final jakarta.persistence.EntityManager entityManager;
    private final AddressRepository addressRepository;
    private final MemberService memberService;
    private final CartService cartService;
    private final ProductRepository productRepository;
    private final DeliveryPolicyProperties deliveryPolicyProperties;

    // 주문서 임시 생성 (결제 직전)
    @Transactional
    public OrderDto.DetailResponse createOrder(Long memberId, OrderDto.CreateRequest request) {
        Member member = memberRepository.findLockedById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        entityManager.refresh(member);
        if (member.getStatus() == MemberStatus.WITHDRAWN) throw new CustomException(ErrorCode.WITHDRAW_MEMBER);

        // 정지회원 주문 차단
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.SUSPENDED_MEMBER);
        }

        // 1. 장바구니 조회 및 빈 장바구니 검증
        Cart initialCart = cartService.getOrCreateCart(memberId);
        Cart cart = cartRepository.findLockedById(initialCart.getId()).orElseThrow();
        entityManager.refresh(cart);
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

        // Lock products in ascending order before reading mutable price/stock snapshots.
        cartItems.stream().map(i -> i.getProduct().getId()).distinct().sorted().forEach(id -> {
            var product = productRepository.findLockedById(id).orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));
            entityManager.refresh(product);
        });

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

        // Aggregate duplicate product lines, then mutate stock in the same lock order.
        var quantities = new java.util.TreeMap<Long,Integer>();
        cartItems.forEach(i -> quantities.merge(i.getProduct().getId(),i.getQuantity(),Math::addExact));
        quantities.forEach((id,quantity) -> {
            if (productRepository.decreaseStockIfEnough(id,quantity) != 1) throw new CustomException(ErrorCode.OUT_OF_STOCK);
            entityManager.refresh(productRepository.findById(id).orElseThrow());
        });

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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderDto.DetailResponse cancelOrder(Long memberId, Long orderId) {
        return paymentWorkflow.cancel(memberId,orderId,false).order();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderDto.DetailResponse updateOrderStatus(Long orderId, OrderDto.StatusUpdateRequest request) {
        if (request.getStatus() == OrderStatus.CANCELED) return paymentWorkflow.cancel(null,orderId,true).order();
        return paymentTransactions.ship(orderId,request);
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

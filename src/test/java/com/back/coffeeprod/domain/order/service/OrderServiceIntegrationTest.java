package com.back.coffeeprod.domain.order.service;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.entity.Cart;
import com.back.coffeeprod.domain.cart.entity.CartItem;
import com.back.coffeeprod.domain.cart.entity.GrindType;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.entity.OrderStatus;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.payment.repository.PaymentRepository;
import com.back.coffeeprod.domain.product.entity.Category;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.product.repository.CategoryRepository;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=test",
        "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key",
        "pg.toss.secret-key=test-secret-key"
})
class OrderServiceIntegrationTest {

    private final OrderService orderService;
    private final MemberRepository memberRepository;
    private final AddressRepository addressRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final EntityManager entityManager;

    @Autowired
    OrderServiceIntegrationTest(
            OrderService orderService,
            MemberRepository memberRepository,
            AddressRepository addressRepository,
            CategoryRepository categoryRepository,
            ProductRepository productRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            EntityManager entityManager
    ) {
        this.orderService = orderService;
        this.memberRepository = memberRepository;
        this.addressRepository = addressRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.entityManager = entityManager;
    }

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void createOrder_deductsMileageAndStock() {
        Member member = saveMember("order-user@test.com", "orderUser", 1_000);
        Address address = saveAddress(member);
        Product product = saveProduct(10, 5_000);
        saveCartItem(member, product, 2);

        OrderDto.DetailResponse response = orderService.createOrder(
                member.getId(),
                createOrderRequest(address.getId(), 300)
        );

        flushAndClear();

        Member reloadedMember = memberRepository.findById(member.getId()).orElseThrow();
        Product reloadedProduct = productRepository.findById(product.getId()).orElseThrow();
        Orders reloadedOrder = orderRepository.findById(response.getOrderId()).orElseThrow();

        assertEquals(700, reloadedMember.getMileage());
        assertEquals(8, reloadedProduct.getStockQuantity());
        assertEquals(10_000, reloadedOrder.getProductTotalPrice());
        assertEquals(3_000, reloadedOrder.getDeliveryFee());
        assertEquals(12_700, reloadedOrder.getTotalPrice());
        assertEquals(300, reloadedOrder.getUsedMileage());
        assertEquals(OrderStatus.PENDING, reloadedOrder.getStatus());
    }

    @Test
    void createOrder_rollsBackMileageWhenStockIsNotEnough() {
        Member member = saveMember("rollback-user@test.com", "rollbackUser", 1_000);
        Address address = saveAddress(member);
        Product product = saveProduct(1, 5_000);
        saveCartItem(member, product, 2);

        CustomException exception = assertThrows(CustomException.class, () ->
                orderService.createOrder(member.getId(), createOrderRequest(address.getId(), 300))
        );

        flushAndClear();

        Member reloadedMember = memberRepository.findById(member.getId()).orElseThrow();
        Product reloadedProduct = productRepository.findById(product.getId()).orElseThrow();

        assertEquals(ErrorCode.OUT_OF_STOCK, exception.getErrorCode());
        assertEquals(1_000, reloadedMember.getMileage());
        assertEquals(1, reloadedProduct.getStockQuantity());
        assertEquals(0, orderRepository.count());
    }

    @Test
    void cancelOrder_restoresStockAndMileageOnce() {
        Member member = saveMember("cancel-user@test.com", "cancelUser", 1_000);
        Address address = saveAddress(member);
        Product product = saveProduct(10, 5_000);
        saveCartItem(member, product, 2);

        OrderDto.DetailResponse created = orderService.createOrder(
                member.getId(),
                createOrderRequest(address.getId(), 300)
        );

        orderService.cancelOrder(member.getId(), created.getOrderId());
        orderService.cancelOrder(member.getId(), created.getOrderId());

        flushAndClear();

        Member reloadedMember = memberRepository.findById(member.getId()).orElseThrow();
        Product reloadedProduct = productRepository.findById(product.getId()).orElseThrow();
        Orders reloadedOrder = orderRepository.findById(created.getOrderId()).orElseThrow();

        assertEquals(1_000, reloadedMember.getMileage());
        assertEquals(10, reloadedProduct.getStockQuantity());
        assertEquals(OrderStatus.CANCELED, reloadedOrder.getStatus());
    }

    @Test
    void getMyOrders_returnsOnlyMembersOrdersWithItems() {
        Member member = saveMember("list-user@test.com", "listUser", 1_000);
        Member otherMember = saveMember("other-list-user@test.com", "otherListUser", 1_000);
        OrderDto.DetailResponse myOrder = createOrder(member, 10, 2, 300);
        createOrder(otherMember, 10, 1, 0);

        // 내 주문 목록 조회는 요청 회원의 주문만 페이지 단위로 반환한다.
        Page<OrderDto.SummaryResponse> response = orderService.getMyOrders(member.getId(), PageRequest.of(0, 10));

        assertEquals(1, response.getTotalElements());
        assertEquals(myOrder.getOrderId(), response.getContent().get(0).getOrderId());
        assertEquals("테스트 원두", response.getContent().get(0).getFirstProductName());
        assertEquals(1, response.getContent().get(0).getItemCount());
    }

    @Test
    void getAllOrders_returnsAdminOrderSummariesWithMemberInfo() {
        Member firstMember = saveMember("admin-list1@test.com", "adminList1", 1_000);
        Member secondMember = saveMember("admin-list2@test.com", "adminList2", 1_000);
        createOrder(firstMember, 10, 2, 300);
        createOrder(secondMember, 10, 1, 0);

        // 관리자 주문 목록 조회는 회원 정보와 주문 요약 정보를 함께 반환한다.
        Page<OrderDto.AdminSummaryResponse> response = orderService.getAllOrders(PageRequest.of(0, 20));

        assertEquals(2, response.getTotalElements());
        assertEquals(2, response.getContent().size());
        assertEquals("테스트 원두", response.getContent().get(0).getFirstProductName());
    }

    @Test
    void updateOrderStatus_changesPaidOrderToShippedWithTrackingNo() {
        Member member = saveMember("ship-user@test.com", "shipUser", 1_000);
        OrderDto.DetailResponse created = createOrder(member, 10, 2, 300);
        orderService.markAsPaid(created.getOrderId());

        OrderDto.DetailResponse response = orderService.updateOrderStatus(
                created.getOrderId(),
                statusUpdateRequest(OrderStatus.SHIPPED, "TRACK-1234")
        );

        assertEquals(OrderStatus.SHIPPED, response.getStatus());
        assertEquals("TRACK-1234", response.getTrackingNo());
    }

    @Test
    void updateOrderStatus_requiresTrackingNoWhenShipping() {
        Member member = saveMember("ship-invalid-user@test.com", "shipInvalidUser", 1_000);
        OrderDto.DetailResponse created = createOrder(member, 10, 2, 300);
        orderService.markAsPaid(created.getOrderId());

        CustomException exception = assertThrows(CustomException.class, () ->
                orderService.updateOrderStatus(
                        created.getOrderId(),
                        statusUpdateRequest(OrderStatus.SHIPPED, null)
                )
        );

        assertEquals(ErrorCode.INVALID_INPUT_VALUE, exception.getErrorCode());
    }

    private OrderDto.DetailResponse createOrder(Member member, int stockQuantity, int quantity, int usedMileage) {
        Address address = saveAddress(member);
        Product product = saveProduct(stockQuantity, 5_000);
        saveCartItem(member, product, quantity);
        return orderService.createOrder(member.getId(), createOrderRequest(address.getId(), usedMileage));
    }

    private Member saveMember(String email, String nickname, int mileage) {
        Member member = Member.builder()
                .email(email)
                .password("encoded-password")
                .name("테스트회원")
                .nickname(nickname)
                .role(Role.USER)
                .build();
        member.addMileage(mileage);
        return memberRepository.save(member);
    }

    private Address saveAddress(Member member) {
        return addressRepository.save(Address.builder()
                .member(member)
                .recipient("테스트수령인")
                .phone("010-1234-5678")
                .zipcode("12345")
                .addressLine1("서울시 테스트구")
                .addressLine2("101호")
                .isDefault(true)
                .build());
    }

    private Product saveProduct(int stockQuantity, int price) {
        Category category = categoryRepository.save(Category.builder()
                .name("테스트 카테고리")
                .build());

        return productRepository.save(Product.builder()
                .category(category)
                .name("테스트 원두")
                .price(price)
                .stockQuantity(stockQuantity)
                .roastLevel(RoastLevel.MEDIUM)
                .description("테스트 상품")
                .imageUrl("https://example.com/coffee.jpg")
                .build());
    }

    private void saveCartItem(Member member, Product product, int quantity) {
        Cart cart = cartRepository.save(Cart.builder()
                .member(member)
                .build());
        CartItem cartItem = CartItem.builder()
                .cart(cart)
                .product(product)
                .quantity(quantity)
                .grindType(GrindType.WHOLE_BEAN)
                .build();
        cart.getCartItems().add(cartItem);
        cartItemRepository.save(cartItem);
    }

    private OrderDto.CreateRequest createOrderRequest(Long addressId, int usedMileage) {
        OrderDto.CreateRequest request = new OrderDto.CreateRequest();
        ReflectionTestUtils.setField(request, "addressId", addressId);
        ReflectionTestUtils.setField(request, "usedMileage", usedMileage);
        return request;
    }

    private OrderDto.StatusUpdateRequest statusUpdateRequest(OrderStatus status, String trackingNo) {
        OrderDto.StatusUpdateRequest request = new OrderDto.StatusUpdateRequest();
        ReflectionTestUtils.setField(request, "status", status);
        ReflectionTestUtils.setField(request, "trackingNo", trackingNo);
        return request;
    }

    private void flushAndClear() {
        entityManager.clear();
    }
}

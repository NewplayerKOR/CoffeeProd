package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.entity.Cart;
import com.back.coffeeprod.domain.cart.entity.CartItem;
import com.back.coffeeprod.domain.cart.entity.GrindType;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.entity.OrderStatus;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.back.coffeeprod.domain.payment.dto.TossPaymentResponse;
import com.back.coffeeprod.domain.payment.gateway.PaymentGateway;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
class PaymentServiceIntegrationTest {

    private final PaymentService paymentService;
    private final OrderService orderService;
    private final MemberRepository memberRepository;
    private final AddressRepository addressRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TestPaymentGateway testPaymentGateway;
    private final EntityManager entityManager;

    @Autowired
    PaymentServiceIntegrationTest(
            PaymentService paymentService,
            OrderService orderService,
            MemberRepository memberRepository,
            AddressRepository addressRepository,
            CategoryRepository categoryRepository,
            ProductRepository productRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            TestPaymentGateway testPaymentGateway,
            EntityManager entityManager
    ) {
        this.paymentService = paymentService;
        this.orderService = orderService;
        this.memberRepository = memberRepository;
        this.addressRepository = addressRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.testPaymentGateway = testPaymentGateway;
        this.entityManager = entityManager;
    }

    @BeforeEach
    void setUp() {
        testPaymentGateway.reset();
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
    void confirmPayment_deniesOtherMembersOrderBeforeGatewayCall() {
        Member owner = saveMember("owner@test.com", "owner", 1_000);
        Member other = saveMember("other@test.com", "other", 1_000);
        OrderDto.DetailResponse order = createOrder(owner, 10, 2, 300);

        CustomException exception = assertThrows(CustomException.class, () ->
                paymentService.confirmPayment(other.getId(), confirmRequest(order.getTossOrderId(), order.getTotalPrice()))
        );

        assertEquals(ErrorCode.ORDER_ACCESS_DENIED, exception.getErrorCode());
        assertEquals(0, testPaymentGateway.getCallCount());
    }

    @Test
    void confirmPayment_savesPaymentAndMarksOrderPaid() {
        Member member = saveMember("success@test.com", "success", 1_000);
        OrderDto.DetailResponse order = createOrder(member, 10, 2, 300);

        PaymentDto.Response response = paymentService.confirmPayment(
                member.getId(),
                confirmRequest(order.getTossOrderId(), order.getTotalPrice())
        );

        flushAndClear();

        Orders reloadedOrder = orderRepository.findById(order.getOrderId()).orElseThrow();

        assertEquals(OrderStatus.PAID, reloadedOrder.getStatus());
        assertEquals(order.getOrderId(), response.getOrderId());
        assertEquals(order.getTossOrderId(), response.getTossOrderId());
        assertEquals("test-payment-key", response.getPaymentKey());
        assertEquals(1, paymentRepository.count());
        assertEquals(1, testPaymentGateway.getCallCount());
    }

    @Test
    void confirmPayment_deniesSuspendedMemberBeforeGatewayCall() {
        Member member = saveMember("suspended-pay@test.com", "suspendedPay", 1_000);
        OrderDto.DetailResponse order = createOrder(member, 10, 2, 300);
        member.updateStatus(MemberStatus.SUSPENDED);
        memberRepository.save(member);

        CustomException exception = assertThrows(CustomException.class, () ->
                paymentService.confirmPayment(member.getId(), confirmRequest(order.getTossOrderId(), order.getTotalPrice()))
        );

        assertEquals(ErrorCode.SUSPENDED_MEMBER, exception.getErrorCode());
        assertEquals(0, testPaymentGateway.getCallCount());
    }

    @Test
    void confirmPayment_deniesAlreadyPaidOrderBeforeGatewayCall() {
        Member member = saveMember("already-paid@test.com", "alreadyPaid", 1_000);
        OrderDto.DetailResponse order = createOrder(member, 10, 2, 300);
        orderService.markAsPaid(order.getOrderId());

        CustomException exception = assertThrows(CustomException.class, () ->
                paymentService.confirmPayment(member.getId(), confirmRequest(order.getTossOrderId(), order.getTotalPrice()))
        );

        assertEquals(ErrorCode.INVALID_ORDER_STATUS, exception.getErrorCode());
        assertEquals(0, testPaymentGateway.getCallCount());
    }

    @Test
    void confirmPayment_cancelsOrderAndRestoresOnAmountMismatch() {
        Member member = saveMember("amount@test.com", "amount", 1_000);
        OrderDto.DetailResponse order = createOrder(member, 10, 2, 300);

        CustomException exception = assertThrows(CustomException.class, () ->
                paymentService.confirmPayment(member.getId(), confirmRequest(order.getTossOrderId(), order.getTotalPrice() + 1))
        );

        flushAndClear();

        Orders reloadedOrder = orderRepository.findById(order.getOrderId()).orElseThrow();
        Member reloadedMember = memberRepository.findById(member.getId()).orElseThrow();
        Product reloadedProduct = productRepository.findAll().get(0);

        assertEquals(ErrorCode.INVALID_ORDER_AMOUNT, exception.getErrorCode());
        assertEquals(OrderStatus.CANCELED, reloadedOrder.getStatus());
        assertEquals(1_000, reloadedMember.getMileage());
        assertEquals(10, reloadedProduct.getStockQuantity());
        assertEquals(0, testPaymentGateway.getCallCount());
    }

    @Test
    void confirmPayment_cancelsOrderAndRestoresOnGatewayFailure() {
        Member member = saveMember("gateway@test.com", "gateway", 1_000);
        OrderDto.DetailResponse order = createOrder(member, 10, 2, 300);
        testPaymentGateway.failWith(ErrorCode.PAYMENT_FAILED);

        CustomException exception = assertThrows(CustomException.class, () ->
                paymentService.confirmPayment(member.getId(), confirmRequest(order.getTossOrderId(), order.getTotalPrice()))
        );

        flushAndClear();

        Orders reloadedOrder = orderRepository.findById(order.getOrderId()).orElseThrow();
        Member reloadedMember = memberRepository.findById(member.getId()).orElseThrow();
        Product reloadedProduct = productRepository.findAll().get(0);

        assertEquals(ErrorCode.PAYMENT_FAILED, exception.getErrorCode());
        assertEquals(OrderStatus.CANCELED, reloadedOrder.getStatus());
        assertEquals(1_000, reloadedMember.getMileage());
        assertEquals(10, reloadedProduct.getStockQuantity());
        assertEquals(1, testPaymentGateway.getCallCount());
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

    private PaymentDto.ConfirmRequest confirmRequest(String tossOrderId, int amount) {
        PaymentDto.ConfirmRequest request = new PaymentDto.ConfirmRequest();
        ReflectionTestUtils.setField(request, "paymentKey", "test-payment-key");
        ReflectionTestUtils.setField(request, "tossOrderId", tossOrderId);
        ReflectionTestUtils.setField(request, "amount", amount);
        return request;
    }

    private void flushAndClear() {
        entityManager.clear();
    }

    @TestConfiguration
    static class PaymentGatewayTestConfig {

        @Bean
        @Primary
        TestPaymentGateway testPaymentGateway() {
            return new TestPaymentGateway();
        }
    }

    static class TestPaymentGateway implements PaymentGateway {

        private int callCount;
        private ErrorCode failureCode;

        @Override
        public TossPaymentResponse confirm(String paymentKey, String tossOrderId, int amount) {
            callCount++;

            if (failureCode != null) {
                throw new CustomException(failureCode);
            }

            TossPaymentResponse response = new TossPaymentResponse();
            ReflectionTestUtils.setField(response, "paymentKey", paymentKey);
            ReflectionTestUtils.setField(response, "orderId", tossOrderId);
            ReflectionTestUtils.setField(response, "status", "DONE");
            ReflectionTestUtils.setField(response, "totalAmount", amount);
            ReflectionTestUtils.setField(response, "method", "CARD");
            ReflectionTestUtils.setField(response, "approvedAt", "2026-05-28T10:00:00");
            return response;
        }

        void failWith(ErrorCode failureCode) {
            this.failureCode = failureCode;
        }

        void reset() {
            this.callCount = 0;
            this.failureCode = null;
        }

        int getCallCount() {
            return callCount;
        }
    }
}

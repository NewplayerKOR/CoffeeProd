package com.back.coffeeprod.domain.product.service;

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
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.payment.repository.PaymentRepository;
import com.back.coffeeprod.domain.product.entity.Category;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.product.repository.CategoryRepository;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=test",
        "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key",
        "pg.toss.secret-key=test-secret-key"
})
class ProductStockConcurrencyTest {

    private final OrderService orderService;
    private final MemberRepository memberRepository;
    private final AddressRepository addressRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    @Autowired
    ProductStockConcurrencyTest(
            OrderService orderService,
            MemberRepository memberRepository,
            AddressRepository addressRepository,
            CategoryRepository categoryRepository,
            ProductRepository productRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository
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
    void createOrder_allowsOnlyOneOrderWhenStockIsOne() throws InterruptedException {
        Product product = saveProduct(1, 5_000);
        Member firstMember = saveMember("stock1@test.com", "stock1");
        Member secondMember = saveMember("stock2@test.com", "stock2");
        Address firstAddress = saveAddress(firstMember);
        Address secondAddress = saveAddress(secondMember);
        saveCartItem(firstMember, product, 1);
        saveCartItem(secondMember, product, 1);

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        submitOrderTask(executorService, readyLatch, startLatch, doneLatch,
                successCount, failCount, firstMember.getId(), firstAddress.getId());
        submitOrderTask(executorService, readyLatch, startLatch, doneLatch,
                successCount, failCount, secondMember.getId(), secondAddress.getId());

        readyLatch.await(3, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        Product reloadedProduct = productRepository.findById(product.getId()).orElseThrow();

        assertEquals(1, successCount.get());
        assertEquals(1, failCount.get());
        assertEquals(0, reloadedProduct.getStockQuantity());
        assertEquals(1, orderRepository.count());
    }

    private void submitOrderTask(
            ExecutorService executorService,
            CountDownLatch readyLatch,
            CountDownLatch startLatch,
            CountDownLatch doneLatch,
            AtomicInteger successCount,
            AtomicInteger failCount,
            Long memberId,
            Long addressId
    ) {
        executorService.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                orderService.createOrder(memberId, createOrderRequest(addressId, 0));
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });
    }

    private Member saveMember(String email, String nickname) {
        return memberRepository.save(Member.builder()
                .email(email)
                .password("encoded-password")
                .name("테스트회원")
                .nickname(nickname)
                .role(Role.USER)
                .build());
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
                .sku("STOCK-TEST-" + category.getId())
                .weightGrams(200)
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
}

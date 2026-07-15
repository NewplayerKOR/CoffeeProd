package com.back.coffeeprod.domain.cart.service;

import com.back.coffeeprod.domain.cart.dto.CartDto;
import com.back.coffeeprod.domain.cart.entity.GrindType;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.payment.repository.PaymentRepository;
import com.back.coffeeprod.domain.product.entity.Category;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.product.repository.CategoryRepository;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.qna.repository.QnaRepository;
import com.back.coffeeprod.domain.review.repository.ReviewRepository;
import com.back.coffeeprod.domain.statistics.repository.SalesStatisticsRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class CartServiceIntegrationTest {

    private final CartService cartService;
    private final PaymentRepository paymentRepository;
    private final ReviewRepository reviewRepository;
    private final QnaRepository qnaRepository;
    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SalesStatisticsRepository salesStatisticsRepository;
    private final MemberRepository memberRepository;
    private int sequence;

    @Autowired
    CartServiceIntegrationTest(
            CartService cartService,
            PaymentRepository paymentRepository,
            ReviewRepository reviewRepository,
            QnaRepository qnaRepository,
            OrderRepository orderRepository,
            CartItemRepository cartItemRepository,
            CartRepository cartRepository,
            AddressRepository addressRepository,
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            SalesStatisticsRepository salesStatisticsRepository,
            MemberRepository memberRepository
    ) {
        this.cartService = cartService;
        this.paymentRepository = paymentRepository;
        this.reviewRepository = reviewRepository;
        this.qnaRepository = qnaRepository;
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.cartRepository = cartRepository;
        this.addressRepository = addressRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.salesStatisticsRepository = salesStatisticsRepository;
        this.memberRepository = memberRepository;
    }

    @BeforeEach
    void setUp() {
        sequence = 0;
        paymentRepository.deleteAll();
        reviewRepository.deleteAll();
        qnaRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        salesStatisticsRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void getCart_createsAnEmptyCartForMember() {
        Member member = saveMember();

        CartDto.CartResponse response = cartService.getCart(member.getId());

        assertEquals(0, response.getTotalQuantity());
        assertEquals(0, response.getTotalPrice());
        assertTrue(cartRepository.findByMemberId(member.getId()).isPresent());
    }

    @Test
    void addItem_mergesQuantityForSameProductAndGrindType() {
        Member member = saveMember();
        Product product = saveProduct(15_000);

        cartService.addItem(member.getId(), addRequest(product.getId(), 1, GrindType.WHOLE_BEAN));
        CartDto.CartResponse response = cartService.addItem(
                member.getId(),
                addRequest(product.getId(), 2, GrindType.WHOLE_BEAN)
        );

        assertEquals(1, response.getItems().size());
        assertEquals(3, response.getTotalQuantity());
        assertEquals(45_000, response.getTotalPrice());
        assertEquals(GrindType.WHOLE_BEAN, response.getItems().get(0).getGrindType());
    }

    @Test
    void addItem_returnsCreatedCartItemInResponse() {
        Member member = saveMember();
        Product product = saveProduct(15_000);

        CartDto.CartResponse response = cartService.addItem(
                member.getId(),
                addRequest(product.getId(), 1, GrindType.WHOLE_BEAN)
        );

        assertEquals(1, response.getItems().size());
        assertEquals(product.getId(), response.getItems().get(0).getProductId());
        assertEquals(15_000, response.getTotalPrice());
    }

    @Test
    void addItem_createsSeparateItemsForDifferentGrindTypes() {
        Member member = saveMember();
        Product product = saveProduct(12_000);

        cartService.addItem(member.getId(), addRequest(product.getId(), 1, GrindType.WHOLE_BEAN));
        CartDto.CartResponse response = cartService.addItem(
                member.getId(),
                addRequest(product.getId(), 2, GrindType.ESPRESSO)
        );

        assertEquals(2, response.getItems().size());
        assertEquals(3, response.getTotalQuantity());
        assertEquals(36_000, response.getTotalPrice());
    }

    @Test
    void updateItem_withZeroQuantityDeletesCartItem() {
        Member member = saveMember();
        Product product = saveProduct(10_000);
        cartService.addItem(member.getId(), addRequest(product.getId(), 2, GrindType.DRIP));
        Long cartItemId = findOnlyCartItemId();

        CartDto.CartResponse response = cartService.updateItem(
                member.getId(),
                cartItemId,
                updateRequest(0, GrindType.DRIP)
        );

        assertEquals(0, response.getItems().size());
        assertEquals(0, cartItemRepository.count());
    }

    @Test
    void updateItem_rejectsCartItemOwnedByAnotherMember() {
        Member owner = saveMember();
        Member otherMember = saveMember();
        Product product = saveProduct(10_000);
        cartService.addItem(owner.getId(), addRequest(product.getId(), 1, GrindType.DRIP));
        Long cartItemId = findOnlyCartItemId();

        CustomException exception = assertThrows(CustomException.class, () ->
                cartService.updateItem(
                        otherMember.getId(),
                        cartItemId,
                        updateRequest(3, GrindType.DRIP)
                )
        );

        assertEquals(ErrorCode.CART_ACCESS_DENIED, exception.getErrorCode());
    }

    @Test
    void addItem_rejectsProductThatIsNotOnSale() {
        Member member = saveMember();
        Product product = saveProduct(10_000);
        product.updateStatus(ProductStatus.HIDDEN);
        productRepository.save(product);

        CustomException exception = assertThrows(CustomException.class, () ->
                cartService.addItem(member.getId(), addRequest(product.getId(), 1, GrindType.WHOLE_BEAN))
        );

        assertEquals(ErrorCode.PRODUCT_NOT_ON_SALE, exception.getErrorCode());
    }

    private Member saveMember() {
        int current = ++sequence;
        return memberRepository.save(Member.builder()
                .email("cart" + current + "@test.com")
                .password("encoded-password")
                .name("Cart Tester")
                .nickname("cartUser" + current)
                .role(Role.USER)
                .build());
    }

    private Product saveProduct(int price) {
        int current = ++sequence;
        Category category = categoryRepository.save(Category.builder()
                .name("Cart Category " + current)
                .build());

        return productRepository.save(Product.builder()
                .category(category)
                .sku("CART-TEST-" + current)
                .weightGrams(200)
                .name("Cart Product " + current)
                .price(price)
                .stockQuantity(20)
                .roastLevel(RoastLevel.MEDIUM)
                .description("Cart service test product")
                .imageUrl("https://example.com/cart-product.jpg")
                .build());
    }

    private CartDto.AddRequest addRequest(Long productId, int quantity, GrindType grindType) {
        CartDto.AddRequest request = new CartDto.AddRequest();
        ReflectionTestUtils.setField(request, "productId", productId);
        ReflectionTestUtils.setField(request, "quantity", quantity);
        ReflectionTestUtils.setField(request, "grindType", grindType);
        return request;
    }

    private CartDto.UpdateRequest updateRequest(int quantity, GrindType grindType) {
        CartDto.UpdateRequest request = new CartDto.UpdateRequest();
        ReflectionTestUtils.setField(request, "quantity", quantity);
        ReflectionTestUtils.setField(request, "grindType", grindType);
        return request;
    }

    private Long findOnlyCartItemId() {
        return cartItemRepository.findAll().get(0).getId();
    }
}

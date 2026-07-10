package com.back.coffeeprod.domain.review.service;

import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.entity.GrindType;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.entity.OrderItem;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.payment.repository.PaymentRepository;
import com.back.coffeeprod.domain.product.entity.Category;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.product.repository.CategoryRepository;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.qna.repository.QnaRepository;
import com.back.coffeeprod.domain.review.dto.ReviewDto;
import com.back.coffeeprod.domain.review.repository.ReviewRepository;
import com.back.coffeeprod.domain.statistics.repository.SalesStatisticsRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
class ReviewServiceIntegrationTest {

    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final QnaRepository qnaRepository;
    private final SalesStatisticsRepository salesStatisticsRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final MemberRepository memberRepository;

    @Autowired
    ReviewServiceIntegrationTest(
            ReviewService reviewService,
            ReviewRepository reviewRepository,
            QnaRepository qnaRepository,
            SalesStatisticsRepository salesStatisticsRepository,
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            CartItemRepository cartItemRepository,
            CartRepository cartRepository,
            AddressRepository addressRepository,
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            MemberRepository memberRepository
    ) {
        this.reviewService = reviewService;
        this.reviewRepository = reviewRepository;
        this.qnaRepository = qnaRepository;
        this.salesStatisticsRepository = salesStatisticsRepository;
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.cartRepository = cartRepository;
        this.addressRepository = addressRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.memberRepository = memberRepository;
    }

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        reviewRepository.deleteAll();
        qnaRepository.deleteAll();
        salesStatisticsRepository.deleteAll();
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
    void create_savesReviewForPurchasedProduct() {
        Member member = saveMember("reviewer@test.com", "reviewer");
        Product product = saveProduct("구매한 원두");
        savePurchasedOrder(member, product);

        ReviewDto.Response response = reviewService.create(
                member.getId(),
                product.getId(),
                reviewRequest(5, "향과 단맛의 균형이 좋습니다.")
        );

        assertEquals("reviewer", response.getNickname());
        assertEquals(5, response.getRating());
        assertEquals("향과 단맛의 균형이 좋습니다.", response.getContent());
        assertEquals(1, reviewRepository.count());
    }

    @Test
    void create_rejectsMemberWithoutPurchaseHistory() {
        Member member = saveMember("non-buyer@test.com", "nonBuyer");
        Product product = saveProduct("미구매 원두");

        CustomException exception = assertThrows(CustomException.class, () ->
                reviewService.create(
                        member.getId(),
                        product.getId(),
                        reviewRequest(4, "구매하지 않은 상품 리뷰")
                )
        );

        assertEquals(ErrorCode.REVIEW_PURCHASE_REQUIRED, exception.getErrorCode());
        assertEquals(0, reviewRepository.count());
    }

    @Test
    void create_rejectsDuplicateReviewForSameProduct() {
        Member member = saveMember("duplicate-review@test.com", "duplicateReview");
        Product product = saveProduct("중복 리뷰 원두");
        savePurchasedOrder(member, product);
        reviewService.create(member.getId(), product.getId(), reviewRequest(5, "첫 번째 리뷰"));

        CustomException exception = assertThrows(CustomException.class, () ->
                reviewService.create(member.getId(), product.getId(), reviewRequest(3, "두 번째 리뷰"))
        );

        assertEquals(ErrorCode.REVIEW_ALREADY_EXISTS, exception.getErrorCode());
        assertEquals(1, reviewRepository.count());
    }

    @Test
    void getReviews_returnsReviewsForProduct() {
        Member firstMember = saveMember("first-review@test.com", "firstReviewer");
        Member secondMember = saveMember("second-review@test.com", "secondReviewer");
        Product product = saveProduct("조회 대상 원두");
        savePurchasedOrder(firstMember, product);
        savePurchasedOrder(secondMember, product);
        reviewService.create(firstMember.getId(), product.getId(), reviewRequest(5, "첫 번째 평가"));
        reviewService.create(secondMember.getId(), product.getId(), reviewRequest(4, "두 번째 평가"));

        Page<ReviewDto.Response> response = reviewService.getReviews(
                product.getId(),
                PageRequest.of(0, 10)
        );

        assertEquals(2, response.getTotalElements());
        assertEquals(2, response.getContent().size());
    }

    @Test
    void update_changesOwnedReview() {
        Member member = saveMember("update-review@test.com", "updateReviewer");
        Product product = saveProduct("수정 대상 원두");
        savePurchasedOrder(member, product);
        ReviewDto.Response created = reviewService.create(
                member.getId(),
                product.getId(),
                reviewRequest(3, "수정 전 내용")
        );

        ReviewDto.Response response = reviewService.update(
                member.getId(),
                created.getId(),
                reviewRequest(5, "수정 후 내용")
        );

        assertEquals(5, response.getRating());
        assertEquals("수정 후 내용", response.getContent());
    }

    @Test
    void update_rejectsOtherMembersReview() {
        Member owner = saveMember("review-owner@test.com", "reviewOwner");
        Member other = saveMember("review-other@test.com", "reviewOther");
        Product product = saveProduct("소유권 검증 원두");
        savePurchasedOrder(owner, product);
        ReviewDto.Response created = reviewService.create(
                owner.getId(),
                product.getId(),
                reviewRequest(4, "작성자 리뷰")
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                reviewService.update(other.getId(), created.getId(), reviewRequest(1, "타인 수정"))
        );

        assertEquals(ErrorCode.REVIEW_ACCESS_DENIED, exception.getErrorCode());
    }

    @Test
    void delete_removesOwnedReview() {
        Member member = saveMember("delete-review@test.com", "deleteReviewer");
        Product product = saveProduct("삭제 대상 원두");
        savePurchasedOrder(member, product);
        ReviewDto.Response created = reviewService.create(
                member.getId(),
                product.getId(),
                reviewRequest(2, "삭제할 리뷰")
        );

        reviewService.delete(member.getId(), created.getId());

        assertFalse(reviewRepository.existsById(created.getId()));
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

    private Product saveProduct(String name) {
        Category category = categoryRepository.save(Category.builder()
                .name(name + " 카테고리")
                .build());

        return productRepository.save(Product.builder()
                .category(category)
                .sku("REVIEW-TEST-" + category.getId())
                .weightGrams(200)
                .name(name)
                .price(15_000)
                .stockQuantity(20)
                .roastLevel(RoastLevel.MEDIUM)
                .description("테스트 상품")
                .imageUrl("https://example.com/coffee.jpg")
                .build());
    }

    private void savePurchasedOrder(Member member, Product product) {
        Orders order = Orders.builder()
                .member(member)
                .tossOrderId("COFFEE-REVIEW-" + member.getId() + "-" + product.getId())
                .productTotalPrice(product.getPrice())
                .deliveryFee(3_000)
                .totalPrice(product.getPrice() + 3_000)
                .usedMileage(0)
                .deliveryAddress("서울시 테스트구")
                .build();
        order.markAsPaid(0);

        OrderItem orderItem = OrderItem.builder()
                .orders(order)
                .product(product)
                .orderPrice(product.getPrice())
                .quantity(1)
                .grindType(GrindType.WHOLE_BEAN)
                .build();
        order.getOrderItems().add(orderItem);

        orderRepository.save(order);
    }

    private ReviewDto.Request reviewRequest(int rating, String content) {
        ReviewDto.Request request = new ReviewDto.Request();
        ReflectionTestUtils.setField(request, "rating", rating);
        ReflectionTestUtils.setField(request, "content", content);
        return request;
    }
}

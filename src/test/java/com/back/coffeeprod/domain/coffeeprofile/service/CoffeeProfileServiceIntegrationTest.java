package com.back.coffeeprod.domain.coffeeprofile.service;

import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeProfileDto;
import com.back.coffeeprod.domain.coffeeprofile.dto.ProcessingMethodDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.repository.CoffeeProfileRepository;
import com.back.coffeeprod.domain.coffeeprofile.repository.ProcessingMethodRepository;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.payment.repository.PaymentRepository;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.product.repository.CategoryRepository;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.qna.repository.QnaRepository;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class CoffeeProfileServiceIntegrationTest {

    private final CoffeeProfileService coffeeProfileService;
    private final ProcessingMethodService processingMethodService;
    private final CoffeeProfileRepository coffeeProfileRepository;
    private final ProcessingMethodRepository processingMethodRepository;
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
    CoffeeProfileServiceIntegrationTest(
            CoffeeProfileService coffeeProfileService,
            ProcessingMethodService processingMethodService,
            CoffeeProfileRepository coffeeProfileRepository,
            ProcessingMethodRepository processingMethodRepository,
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
        this.coffeeProfileService = coffeeProfileService;
        this.processingMethodService = processingMethodService;
        this.coffeeProfileRepository = coffeeProfileRepository;
        this.processingMethodRepository = processingMethodRepository;
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

    @Test
    void createCoffeeProfile_savesSingleOriginWithProcessingMethod() {
        ProcessingMethodDto.Response method = processingMethodService.createProcessingMethod(
                processingMethodRequest("WASHED", "Washed", "수세식")
        );

        CoffeeProfileDto.Response response = coffeeProfileService.createCoffeeProfile(
                coffeeProfileRequest(
                        method.getId(),
                        "에티오피아 예가체프",
                        BeanType.SINGLE_ORIGIN,
                        "ET",
                        false,
                        null,
                        1_800,
                        2_100
                )
        );

        assertEquals("에티오피아 예가체프", response.getProfileName());
        assertEquals(BeanType.SINGLE_ORIGIN, response.getBeanType());
        assertEquals("ET", response.getOriginCountryCode());
        assertEquals("WASHED", response.getProcessingMethod().getCode());
        assertNotNull(response.getCreatedAt());
        assertEquals(1, coffeeProfileRepository.count());
    }

    @Test
    void createCoffeeProfile_rejectsSingleOriginWithoutCountryCode() {
        CustomException exception = assertThrows(CustomException.class, () ->
                coffeeProfileService.createCoffeeProfile(
                        coffeeProfileRequest(null, "원산지 없는 싱글", BeanType.SINGLE_ORIGIN, null, false, null, null, null)
                )
        );

        assertEquals(ErrorCode.INVALID_COFFEE_PROFILE, exception.getErrorCode());
    }

    @Test
    void createCoffeeProfile_rejectsBlendWithCountryCode() {
        CustomException exception = assertThrows(CustomException.class, () ->
                coffeeProfileService.createCoffeeProfile(
                        coffeeProfileRequest(null, "국가가 지정된 블렌드", BeanType.BLEND, "BR", false, null, null, null)
                )
        );

        assertEquals(ErrorCode.INVALID_COFFEE_PROFILE, exception.getErrorCode());
    }

    @Test
    void createCoffeeProfile_rejectsDecafMethodForRegularCoffee() {
        CustomException exception = assertThrows(CustomException.class, () ->
                coffeeProfileService.createCoffeeProfile(
                        coffeeProfileRequest(null, "일반 원두", BeanType.SINGLE_ORIGIN, "CO", false, "SWISS_WATER", null, null)
                )
        );

        assertEquals(ErrorCode.INVALID_COFFEE_PROFILE, exception.getErrorCode());
    }

    @Test
    void createCoffeeProfile_rejectsInvalidAltitudeRange() {
        CustomException exception = assertThrows(CustomException.class, () ->
                coffeeProfileService.createCoffeeProfile(
                        coffeeProfileRequest(null, "고도 역전 원두", BeanType.SINGLE_ORIGIN, "CO", false, null, 2_000, 1_500)
                )
        );

        assertEquals(ErrorCode.INVALID_COFFEE_PROFILE, exception.getErrorCode());
    }

    @Test
    void updateCoffeeProfile_changesProcessingMethodAndProfileData() {
        ProcessingMethodDto.Response washed = processingMethodService.createProcessingMethod(
                processingMethodRequest("WASHED", "Washed", "수세식")
        );
        ProcessingMethodDto.Response natural = processingMethodService.createProcessingMethod(
                processingMethodRequest("NATURAL", "Natural", "건식")
        );
        CoffeeProfileDto.Response created = coffeeProfileService.createCoffeeProfile(
                coffeeProfileRequest(washed.getId(), "수정 전 프로필", BeanType.SINGLE_ORIGIN, "ET", false, null, 1_800, 2_000)
        );

        CoffeeProfileDto.Response response = coffeeProfileService.updateCoffeeProfile(
                created.getId(),
                coffeeProfileRequest(natural.getId(), "수정 후 프로필", BeanType.SINGLE_ORIGIN, "KE", true, "SWISS_WATER", 1_600, 1_900)
        );

        assertEquals("수정 후 프로필", response.getProfileName());
        assertEquals("KE", response.getOriginCountryCode());
        assertEquals("NATURAL", response.getProcessingMethod().getCode());
        assertEquals("SWISS_WATER", response.getDecafMethod());
    }

    @Test
    void createProcessingMethod_rejectsDuplicateCode() {
        processingMethodService.createProcessingMethod(processingMethodRequest("WASHED", "Washed", "수세식"));

        CustomException exception = assertThrows(CustomException.class, () ->
                processingMethodService.createProcessingMethod(processingMethodRequest("WASHED", "Different", "중복 코드"))
        );

        assertEquals(ErrorCode.DUPLICATE_PROCESSING_METHOD_CODE, exception.getErrorCode());
    }

    @Test
    void getProcessingMethods_returnsMethodsOrderedByName() {
        processingMethodService.createProcessingMethod(processingMethodRequest("WASHED", "Washed", "수세식"));
        processingMethodService.createProcessingMethod(processingMethodRequest("NATURAL", "Natural", "건식"));

        List<ProcessingMethodDto.Response> response = processingMethodService.getProcessingMethods();

        assertEquals(2, response.size());
        assertEquals("Natural", response.get(0).getName());
        assertEquals("Washed", response.get(1).getName());
    }

    @Test
    void getCoffeeProfiles_returnsPagedProfiles() {
        coffeeProfileService.createCoffeeProfile(
                coffeeProfileRequest(null, "첫 번째 프로필", BeanType.SINGLE_ORIGIN, "ET", false, null, null, null)
        );
        coffeeProfileService.createCoffeeProfile(
                coffeeProfileRequest(null, "두 번째 프로필", BeanType.BLEND, null, false, null, null, null)
        );

        Page<CoffeeProfileDto.Response> response = coffeeProfileService.getCoffeeProfiles(PageRequest.of(0, 10));

        assertEquals(2, response.getTotalElements());
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
        coffeeProfileRepository.deleteAll();
        processingMethodRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private ProcessingMethodDto.CreateRequest processingMethodRequest(
            String code,
            String name,
            String description
    ) {
        ProcessingMethodDto.CreateRequest request = new ProcessingMethodDto.CreateRequest();
        ReflectionTestUtils.setField(request, "code", code);
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "description", description);
        return request;
    }

    private CoffeeProfileDto.Request coffeeProfileRequest(
            Long processingMethodId,
            String profileName,
            BeanType beanType,
            String originCountryCode,
            boolean decaf,
            String decafMethod,
            Integer altitudeMin,
            Integer altitudeMax
    ) {
        CoffeeProfileDto.Request request = new CoffeeProfileDto.Request();
        ReflectionTestUtils.setField(request, "processingMethodId", processingMethodId);
        ReflectionTestUtils.setField(request, "profileName", profileName);
        ReflectionTestUtils.setField(request, "beanType", beanType);
        ReflectionTestUtils.setField(request, "originCountryCode", originCountryCode);
        ReflectionTestUtils.setField(request, "originRegion", "테스트 지역");
        ReflectionTestUtils.setField(request, "farmOrCooperative", "테스트 농장");
        ReflectionTestUtils.setField(request, "producer", "테스트 생산자");
        ReflectionTestUtils.setField(request, "altitudeMin", altitudeMin);
        ReflectionTestUtils.setField(request, "altitudeMax", altitudeMax);
        ReflectionTestUtils.setField(request, "roastLevel", RoastLevel.MEDIUM);
        ReflectionTestUtils.setField(request, "decaf", decaf);
        ReflectionTestUtils.setField(request, "decafMethod", decafMethod);
        ReflectionTestUtils.setField(request, "acidity", (short) 3);
        ReflectionTestUtils.setField(request, "body", (short) 3);
        ReflectionTestUtils.setField(request, "sweetness", (short) 3);
        ReflectionTestUtils.setField(request, "aroma", (short) 3);
        ReflectionTestUtils.setField(request, "summary", "테스트 커피 프로필");
        return request;
    }
}

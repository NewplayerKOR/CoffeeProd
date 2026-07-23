package com.back.coffeeprod.domain.recommendation.service;

import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import com.back.coffeeprod.domain.coffeeprofile.repository.CoffeeProfileRepository;
import com.back.coffeeprod.domain.coffeeprofile.repository.ProcessingMethodRepository;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.product.entity.Category;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.product.repository.CategoryRepository;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.recommendation.dto.CoffeeRecommendationDto;
import com.back.coffeeprod.domain.recommendation.dto.MemberCoffeePreferenceDto;
import com.back.coffeeprod.domain.recommendation.repository.MemberCoffeePreferenceRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:coffee-recommendation-test;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=test",
        "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key",
        "pg.toss.secret-key=test-secret-key"
})
class CoffeeRecommendationServiceIntegrationTest {

    private final CoffeeRecommendationService recommendationService;
    private final MemberCoffeePreferenceService preferenceService;
    private final MemberCoffeePreferenceRepository preferenceRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final CoffeeProfileRepository coffeeProfileRepository;
    private final ProcessingMethodRepository processingMethodRepository;
    private final MemberRepository memberRepository;
    private int sequence;

    @Autowired
    CoffeeRecommendationServiceIntegrationTest(
            CoffeeRecommendationService recommendationService,
            MemberCoffeePreferenceService preferenceService,
            MemberCoffeePreferenceRepository preferenceRepository,
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            CoffeeProfileRepository coffeeProfileRepository,
            ProcessingMethodRepository processingMethodRepository,
            MemberRepository memberRepository
    ) {
        this.recommendationService = recommendationService;
        this.preferenceService = preferenceService;
        this.preferenceRepository = preferenceRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.coffeeProfileRepository = coffeeProfileRepository;
        this.processingMethodRepository = processingMethodRepository;
        this.memberRepository = memberRepository;
    }

    @BeforeEach
    void setUp() {
        sequence = 0;
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void recommend_ranksExactMatchFirstAndExcludesUnavailableProducts() {
        Category category = saveCategory();
        ProcessingMethod washed = saveProcessingMethod("WASHED", "Washed");
        ProcessingMethod natural = saveProcessingMethod("NATURAL", "Natural");
        CoffeeProfile exactProfile = saveProfile(
                "Exact Match",
                washed,
                BeanType.SINGLE_ORIGIN,
                RoastLevel.MEDIUM,
                false,
                5, 3, 4, 5
        );
        CoffeeProfile nearProfile = saveProfile(
                "Near Match",
                natural,
                BeanType.BLEND,
                RoastLevel.DARK,
                false,
                4, 4, 3, 4
        );
        Product exactProduct = saveProduct(category, exactProfile, "Exact Product", 18_000, 10);
        saveProduct(category, nearProfile, "Near Product", 15_000, 10);
        Product hiddenProduct = saveProduct(category, exactProfile, "Hidden Product", 10_000, 10);
        hiddenProduct.updateStatus(ProductStatus.HIDDEN);
        productRepository.save(hiddenProduct);
        saveProduct(category, exactProfile, "Out Of Stock", 9_000, 0);
        saveProduct(category, null, "No Profile", 8_000, 10);

        List<CoffeeRecommendationDto.Response> response = recommendationService.recommend(
                recommendationRequest(
                        washed.getId(),
                        BeanType.SINGLE_ORIGIN,
                        RoastLevel.MEDIUM,
                        false,
                        5, 3, 4, 5,
                        5
                )
        );

        assertEquals(2, response.size());
        assertEquals(exactProduct.getId(), response.get(0).getProductId());
        assertEquals(26, response.get(0).getRecommendationScore());
        assertTrue(response.get(0).getReasons().contains("선호 가공 방식과 일치"));
        assertEquals("Near Product", response.get(1).getName());
    }

    @Test
    void recommend_filtersByDecafPreference() {
        Category category = saveCategory();
        CoffeeProfile regular = saveProfile(
                "Regular", null, BeanType.SINGLE_ORIGIN, RoastLevel.MEDIUM, false, 3, 3, 3, 3
        );
        CoffeeProfile decaf = saveProfile(
                "Decaf", null, BeanType.SINGLE_ORIGIN, RoastLevel.MEDIUM, true, 3, 3, 3, 3
        );
        saveProduct(category, regular, "Regular Product", 10_000, 10);
        Product decafProduct = saveProduct(category, decaf, "Decaf Product", 11_000, 10);

        List<CoffeeRecommendationDto.Response> response = recommendationService.recommend(
                recommendationRequest(null, null, null, true, null, null, null, null, null)
        );

        assertEquals(1, response.size());
        assertEquals(decafProduct.getId(), response.get(0).getProductId());
        assertEquals(1, response.get(0).getRecommendationScore());
    }

    @Test
    void recommend_usesLowerPriceAsTieBreakerAndAppliesLimit() {
        Category category = saveCategory();
        CoffeeProfile profile = saveProfile(
                "Tie Profile", null, BeanType.BLEND, RoastLevel.DARK, false, 3, 3, 3, 3
        );
        saveProduct(category, profile, "Expensive", 20_000, 10);
        Product cheaper = saveProduct(category, profile, "Cheaper", 15_000, 10);

        List<CoffeeRecommendationDto.Response> response = recommendationService.recommend(
                recommendationRequest(null, BeanType.BLEND, null, null, null, null, null, null, 1)
        );

        assertEquals(1, response.size());
        assertEquals(cheaper.getId(), response.get(0).getProductId());
    }

    @Test
    void recommend_rejectsRequestWithoutAnyPreference() {
        CustomException exception = assertThrows(CustomException.class, () ->
                recommendationService.recommend(new CoffeeRecommendationDto.Request())
        );

        assertEquals(ErrorCode.RECOMMENDATION_PREFERENCE_REQUIRED, exception.getErrorCode());
    }

    @Test
    void recommend_rejectsInvalidLimit() {
        CoffeeRecommendationDto.Request request = recommendationRequest(
                null, null, RoastLevel.MEDIUM, null, null, null, null, null, 11
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                recommendationService.recommend(request)
        );

        assertEquals(ErrorCode.INVALID_RECOMMENDATION_LIMIT, exception.getErrorCode());
    }

    @Test
    void recommend_rejectsUnknownProcessingMethod() {
        CoffeeRecommendationDto.Request request = recommendationRequest(
                999_999L, null, null, null, null, null, null, null, 5
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                recommendationService.recommend(request)
        );

        assertEquals(ErrorCode.PROCESSING_METHOD_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void recommendForMember_usesSavedPreference() {
        Member member = saveMember();
        Category category = saveCategory();
        CoffeeProfile lightProfile = saveProfile(
                "Light Profile", null, BeanType.SINGLE_ORIGIN, RoastLevel.LIGHT, false, 4, 2, 4, 5
        );
        Product lightProduct = saveProduct(category, lightProfile, "Light Product", 18_000, 10);
        preferenceService.upsertMyPreference(
                member.getId(),
                preferenceRequest(BeanType.SINGLE_ORIGIN, RoastLevel.LIGHT, (short) 4)
        );

        List<CoffeeRecommendationDto.Response> response = recommendationService.recommendForMember(
                member.getId(),
                3
        );

        assertEquals(1, response.size());
        assertEquals(lightProduct.getId(), response.get(0).getProductId());
        assertTrue(response.get(0).getRecommendationScore() > 0);
    }

    @Test
    void recommendForMember_rejectsMemberWithoutSavedPreference() {
        Member member = saveMember();

        CustomException exception = assertThrows(CustomException.class, () ->
                recommendationService.recommendForMember(member.getId(), 3)
        );

        assertEquals(ErrorCode.COFFEE_PREFERENCE_NOT_FOUND, exception.getErrorCode());
    }

    private void cleanUp() {
        preferenceRepository.deleteAll();
        productRepository.deleteAll();
        coffeeProfileRepository.deleteAll();
        processingMethodRepository.deleteAll();
        categoryRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private Member saveMember() {
        int current = ++sequence;
        return memberRepository.save(Member.builder()
                .email("recommendation" + current + "@test.com")
                .password("encoded-password")
                .name("Recommendation Tester")
                .nickname("recommendation" + current)
                .role(Role.USER)
                .build());
    }

    private Category saveCategory() {
        return categoryRepository.save(Category.builder()
                .name("Recommendation Category " + ++sequence)
                .build());
    }

    private ProcessingMethod saveProcessingMethod(String code, String name) {
        return processingMethodRepository.save(ProcessingMethod.builder()
                .code(code)
                .name(name)
                .description(name + " processing")
                .build());
    }

    private CoffeeProfile saveProfile(
            String name,
            ProcessingMethod processingMethod,
            BeanType beanType,
            RoastLevel roastLevel,
            boolean decaf,
            int acidity,
            int body,
            int sweetness,
            int aroma
    ) {
        return coffeeProfileRepository.save(CoffeeProfile.builder()
                .processingMethod(processingMethod)
                .profileName(name)
                .beanType(beanType)
                .originCountryCode(beanType == BeanType.SINGLE_ORIGIN ? "ET" : null)
                .originRegion(beanType == BeanType.SINGLE_ORIGIN ? "Test Region" : null)
                .roastLevel(roastLevel)
                .decaf(decaf)
                .decafMethod(decaf ? "SWISS_WATER" : null)
                .acidity((short) acidity)
                .body((short) body)
                .sweetness((short) sweetness)
                .aroma((short) aroma)
                .summary("Recommendation profile")
                .build());
    }

    private Product saveProduct(
            Category category,
            CoffeeProfile profile,
            String name,
            int price,
            int stockQuantity
    ) {
        int current = ++sequence;
        return productRepository.save(Product.builder()
                .category(category)
                .coffeeProfile(profile)
                .sku("RECOMMENDATION-" + current)
                .weightGrams(200)
                .name(name)
                .price(price)
                .stockQuantity(stockQuantity)
                .roastLevel(profile == null ? RoastLevel.MEDIUM : profile.getRoastLevel())
                .description("Recommendation product")
                .imageUrl("https://example.com/recommendation.jpg")
                .build());
    }

    private CoffeeRecommendationDto.Request recommendationRequest(
            Long processingMethodId,
            BeanType beanType,
            RoastLevel roastLevel,
            Boolean decaf,
            Integer acidity,
            Integer body,
            Integer sweetness,
            Integer aroma,
            Integer limit
    ) {
        CoffeeRecommendationDto.Request request = new CoffeeRecommendationDto.Request();
        ReflectionTestUtils.setField(request, "processingMethodId", processingMethodId);
        ReflectionTestUtils.setField(request, "beanType", beanType);
        ReflectionTestUtils.setField(request, "roastLevel", roastLevel);
        ReflectionTestUtils.setField(request, "decaf", decaf);
        ReflectionTestUtils.setField(request, "preferredAcidity", acidity);
        ReflectionTestUtils.setField(request, "preferredBody", body);
        ReflectionTestUtils.setField(request, "preferredSweetness", sweetness);
        ReflectionTestUtils.setField(request, "preferredAroma", aroma);
        ReflectionTestUtils.setField(request, "limit", limit);
        return request;
    }

    private MemberCoffeePreferenceDto.Request preferenceRequest(
            BeanType beanType,
            RoastLevel roastLevel,
            Short preferredAcidity
    ) {
        MemberCoffeePreferenceDto.Request request = new MemberCoffeePreferenceDto.Request();
        ReflectionTestUtils.setField(request, "beanType", beanType);
        ReflectionTestUtils.setField(request, "roastLevel", roastLevel);
        ReflectionTestUtils.setField(request, "preferredAcidity", preferredAcidity);
        return request;
    }
}

package com.back.coffeeprod.domain.coffeeprofile.service;

import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeProfileDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.entity.BrewMethod;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeVariety;
import com.back.coffeeprod.domain.coffeeprofile.entity.FlavorNote;
import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import com.back.coffeeprod.domain.coffeeprofile.repository.BrewMethodRepository;
import com.back.coffeeprod.domain.coffeeprofile.repository.CoffeeProfileRepository;
import com.back.coffeeprod.domain.coffeeprofile.repository.CoffeeVarietyRepository;
import com.back.coffeeprod.domain.coffeeprofile.repository.FlavorNoteRepository;
import com.back.coffeeprod.domain.coffeeprofile.repository.ProcessingMethodRepository;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:coffee-profile-catalog-test;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=test",
        "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key",
        "pg.toss.secret-key=test-secret-key"
})
@DisplayName("커피 프로필 카탈로그 연관관계 통합 테스트")
class CoffeeProfileCatalogIntegrationTest {

    private final CoffeeProfileService coffeeProfileService;
    private final CoffeeProfileRepository coffeeProfileRepository;
    private final ProcessingMethodRepository processingMethodRepository;
    private final FlavorNoteRepository flavorNoteRepository;
    private final BrewMethodRepository brewMethodRepository;
    private final CoffeeVarietyRepository coffeeVarietyRepository;

    @Autowired
    CoffeeProfileCatalogIntegrationTest(
            CoffeeProfileService coffeeProfileService,
            CoffeeProfileRepository coffeeProfileRepository,
            ProcessingMethodRepository processingMethodRepository,
            FlavorNoteRepository flavorNoteRepository,
            BrewMethodRepository brewMethodRepository,
            CoffeeVarietyRepository coffeeVarietyRepository
    ) {
        this.coffeeProfileService = coffeeProfileService;
        this.coffeeProfileRepository = coffeeProfileRepository;
        this.processingMethodRepository = processingMethodRepository;
        this.flavorNoteRepository = flavorNoteRepository;
        this.brewMethodRepository = brewMethodRepository;
        this.coffeeVarietyRepository = coffeeVarietyRepository;
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
    @DisplayName("커피 프로필 생성 시 카탈로그 연관관계를 요청 순서대로 저장한다")
    void createCoffeeProfile_savesCatalogRelationsInRequestOrder() {
        FlavorNote citrus = saveFlavorNote("CITRUS", "Citrus");
        FlavorNote floral = saveFlavorNote("FLORAL", "Floral");
        BrewMethod v60 = saveBrewMethod("V60", "V60");
        BrewMethod aeropress = saveBrewMethod("AEROPRESS", "AeroPress");
        CoffeeVariety bourbon = saveVariety("BOURBON", "Bourbon");
        CoffeeVariety caturra = saveVariety("CATURRA", "Caturra");
        CoffeeProfileDto.Request request = singleOriginRequest("Ordered Profile");
        setCatalogRelations(
                request,
                List.of(flavorRequest(floral.getId(), 5), flavorRequest(citrus.getId(), 3)),
                List.of(brewRequest(aeropress.getId(), "Strong recipe"), brewRequest(v60.getId(), "Clean recipe")),
                List.of(varietyRequest(caturra.getId()), varietyRequest(bourbon.getId()))
        );

        CoffeeProfileDto.Response response = coffeeProfileService.createCoffeeProfile(request);

        assertEquals(List.of("FLORAL", "CITRUS"), response.getFlavorNotes().stream().map(CoffeeProfileDto.FlavorNoteResponse::getCode).toList());
        assertEquals((short) 5, response.getFlavorNotes().get(0).getIntensity());
        assertEquals(List.of("AEROPRESS", "V60"), response.getBrewMethods().stream().map(CoffeeProfileDto.BrewMethodResponse::getCode).toList());
        assertEquals("Strong recipe", response.getBrewMethods().get(0).getRecommendationNote());
        assertEquals(List.of("CATURRA", "BOURBON"), response.getVarieties().stream().map(CoffeeProfileDto.VarietyResponse::getCode).toList());
    }

    @Test
    @DisplayName("커피 프로필 수정 시 기존 카탈로그 연관관계를 교체한다")
    void updateCoffeeProfile_replacesExistingCatalogRelations() {
        FlavorNote citrus = saveFlavorNote("CITRUS", "Citrus");
        FlavorNote chocolate = saveFlavorNote("CHOCOLATE", "Chocolate");
        BrewMethod v60 = saveBrewMethod("V60", "V60");
        BrewMethod frenchPress = saveBrewMethod("FRENCH_PRESS", "French Press");
        CoffeeVariety bourbon = saveVariety("BOURBON", "Bourbon");
        CoffeeVariety geisha = saveVariety("GEISHA", "Geisha");
        CoffeeProfileDto.Request createRequest = singleOriginRequest("Before Update");
        setCatalogRelations(
                createRequest,
                List.of(flavorRequest(citrus.getId(), 3)),
                List.of(brewRequest(v60.getId(), "Before")),
                List.of(varietyRequest(bourbon.getId()))
        );
        CoffeeProfileDto.Response created = coffeeProfileService.createCoffeeProfile(createRequest);
        CoffeeProfileDto.Request updateRequest = singleOriginRequest("After Update");
        setCatalogRelations(
                updateRequest,
                List.of(flavorRequest(chocolate.getId(), 5)),
                List.of(brewRequest(frenchPress.getId(), "After")),
                List.of(varietyRequest(geisha.getId()))
        );

        CoffeeProfileDto.Response response = coffeeProfileService.updateCoffeeProfile(created.getId(), updateRequest);

        assertEquals("After Update", response.getProfileName());
        assertEquals(List.of("CHOCOLATE"), response.getFlavorNotes().stream().map(CoffeeProfileDto.FlavorNoteResponse::getCode).toList());
        assertEquals(List.of("FRENCH_PRESS"), response.getBrewMethods().stream().map(CoffeeProfileDto.BrewMethodResponse::getCode).toList());
        assertEquals(List.of("GEISHA"), response.getVarieties().stream().map(CoffeeProfileDto.VarietyResponse::getCode).toList());
    }

    @Test
    @DisplayName("중복된 향미 노트가 포함된 커피 프로필은 생성할 수 없다")
    void createCoffeeProfile_rejectsDuplicateFlavorNote() {
        FlavorNote citrus = saveFlavorNote("CITRUS", "Citrus");
        CoffeeProfileDto.Request request = singleOriginRequest("Duplicate Flavor");
        ReflectionTestUtils.setField(
                request,
                "flavorNotes",
                List.of(flavorRequest(citrus.getId(), 3), flavorRequest(citrus.getId(), 5))
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                coffeeProfileService.createCoffeeProfile(request)
        );

        assertEquals(ErrorCode.DUPLICATE_COFFEE_PROFILE_FLAVOR_NOTE, exception.getErrorCode());
    }

    @Test
    @DisplayName("중복된 추출 방식이 포함된 커피 프로필은 생성할 수 없다")
    void createCoffeeProfile_rejectsDuplicateBrewMethod() {
        BrewMethod v60 = saveBrewMethod("V60", "V60");
        CoffeeProfileDto.Request request = singleOriginRequest("Duplicate Brew");
        ReflectionTestUtils.setField(
                request,
                "brewMethods",
                List.of(brewRequest(v60.getId(), "First"), brewRequest(v60.getId(), "Second"))
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                coffeeProfileService.createCoffeeProfile(request)
        );

        assertEquals(ErrorCode.DUPLICATE_COFFEE_PROFILE_BREW_METHOD, exception.getErrorCode());
    }

    @Test
    @DisplayName("중복된 품종이 포함된 커피 프로필은 생성할 수 없다")
    void createCoffeeProfile_rejectsDuplicateVariety() {
        CoffeeVariety bourbon = saveVariety("BOURBON", "Bourbon");
        CoffeeProfileDto.Request request = singleOriginRequest("Duplicate Variety");
        ReflectionTestUtils.setField(
                request,
                "varieties",
                List.of(varietyRequest(bourbon.getId()), varietyRequest(bourbon.getId()))
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                coffeeProfileService.createCoffeeProfile(request)
        );

        assertEquals(ErrorCode.DUPLICATE_COFFEE_PROFILE_VARIETY, exception.getErrorCode());
    }

    @Test
    @DisplayName("블렌드 구성 비율의 합이 100이면 구성 원두를 저장한다")
    void createCoffeeProfile_savesBlendComponentsWhenRatiosTotalOneHundred() {
        ProcessingMethod washed = saveProcessingMethod("WASHED", "Washed");
        ProcessingMethod natural = saveProcessingMethod("NATURAL", "Natural");
        CoffeeProfileDto.Request request = blendRequest("House Blend");
        ReflectionTestUtils.setField(
                request,
                "components",
                List.of(
                        componentRequest("BR", "Cerrado", natural.getId(), "60.00"),
                        componentRequest("ET", "Yirgacheffe", washed.getId(), "40.00")
                )
        );

        CoffeeProfileDto.Response response = coffeeProfileService.createCoffeeProfile(request);

        assertEquals(2, response.getComponents().size());
        assertEquals("BR", response.getComponents().get(0).getOriginCountryCode());
        assertEquals(new BigDecimal("60.00"), response.getComponents().get(0).getComponentRatio());
        assertEquals("NATURAL", response.getComponents().get(0).getProcessingMethod().getCode());
        assertEquals("ET", response.getComponents().get(1).getOriginCountryCode());
    }

    @Test
    @DisplayName("블렌드 구성 비율의 합이 100이 아니면 생성할 수 없다")
    void createCoffeeProfile_rejectsBlendWhenComponentRatiosDoNotTotalOneHundred() {
        CoffeeProfileDto.Request request = blendRequest("Invalid Blend");
        ReflectionTestUtils.setField(
                request,
                "components",
                List.of(
                        componentRequest("BR", null, null, "50.00"),
                        componentRequest("ET", null, null, "40.00")
                )
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                coffeeProfileService.createCoffeeProfile(request)
        );

        assertEquals(ErrorCode.INVALID_BLEND_COMPONENT_RATIO, exception.getErrorCode());
    }

    @Test
    @DisplayName("싱글 오리진 프로필에는 블렌드 구성 원두를 등록할 수 없다")
    void createCoffeeProfile_rejectsComponentsForSingleOrigin() {
        CoffeeProfileDto.Request request = singleOriginRequest("Invalid Single Origin");
        ReflectionTestUtils.setField(
                request,
                "components",
                List.of(
                        componentRequest("ET", null, null, "50.00"),
                        componentRequest("KE", null, null, "50.00")
                )
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                coffeeProfileService.createCoffeeProfile(request)
        );

        assertEquals(ErrorCode.INVALID_COFFEE_PROFILE, exception.getErrorCode());
    }

    private void cleanUp() {
        coffeeProfileRepository.deleteAll();
        flavorNoteRepository.deleteAll();
        brewMethodRepository.deleteAll();
        coffeeVarietyRepository.deleteAll();
        processingMethodRepository.deleteAll();
    }

    private CoffeeProfileDto.Request singleOriginRequest(String name) {
        return baseRequest(name, BeanType.SINGLE_ORIGIN, "ET");
    }

    private CoffeeProfileDto.Request blendRequest(String name) {
        CoffeeProfileDto.Request request = baseRequest(name, BeanType.BLEND, null);
        ReflectionTestUtils.setField(request, "originRegion", null);
        ReflectionTestUtils.setField(request, "farmOrCooperative", null);
        ReflectionTestUtils.setField(request, "producer", null);
        ReflectionTestUtils.setField(request, "altitudeMin", null);
        ReflectionTestUtils.setField(request, "altitudeMax", null);
        return request;
    }

    private CoffeeProfileDto.Request baseRequest(String name, BeanType beanType, String countryCode) {
        CoffeeProfileDto.Request request = new CoffeeProfileDto.Request();
        ReflectionTestUtils.setField(request, "profileName", name);
        ReflectionTestUtils.setField(request, "beanType", beanType);
        ReflectionTestUtils.setField(request, "originCountryCode", countryCode);
        ReflectionTestUtils.setField(request, "originRegion", "Test Region");
        ReflectionTestUtils.setField(request, "farmOrCooperative", "Test Farm");
        ReflectionTestUtils.setField(request, "producer", "Test Producer");
        ReflectionTestUtils.setField(request, "altitudeMin", 1_500);
        ReflectionTestUtils.setField(request, "altitudeMax", 1_900);
        ReflectionTestUtils.setField(request, "roastLevel", RoastLevel.MEDIUM);
        ReflectionTestUtils.setField(request, "decaf", false);
        ReflectionTestUtils.setField(request, "acidity", (short) 4);
        ReflectionTestUtils.setField(request, "body", (short) 3);
        ReflectionTestUtils.setField(request, "sweetness", (short) 4);
        ReflectionTestUtils.setField(request, "aroma", (short) 5);
        ReflectionTestUtils.setField(request, "summary", "Catalog integration profile");
        return request;
    }

    private void setCatalogRelations(
            CoffeeProfileDto.Request request,
            List<CoffeeProfileDto.FlavorNoteRequest> flavorNotes,
            List<CoffeeProfileDto.BrewMethodRequest> brewMethods,
            List<CoffeeProfileDto.VarietyRequest> varieties
    ) {
        ReflectionTestUtils.setField(request, "flavorNotes", flavorNotes);
        ReflectionTestUtils.setField(request, "brewMethods", brewMethods);
        ReflectionTestUtils.setField(request, "varieties", varieties);
    }

    private FlavorNote saveFlavorNote(String code, String name) {
        return flavorNoteRepository.save(FlavorNote.builder()
                .code(code)
                .name(name)
                .description(name + " flavor")
                .build());
    }

    private BrewMethod saveBrewMethod(String code, String name) {
        return brewMethodRepository.save(BrewMethod.builder()
                .code(code)
                .name(name)
                .description(name + " brew")
                .build());
    }

    private CoffeeVariety saveVariety(String code, String name) {
        return coffeeVarietyRepository.save(CoffeeVariety.builder()
                .code(code)
                .name(name)
                .description(name + " variety")
                .build());
    }

    private ProcessingMethod saveProcessingMethod(String code, String name) {
        return processingMethodRepository.save(ProcessingMethod.builder()
                .code(code)
                .name(name)
                .description(name + " processing")
                .build());
    }

    private CoffeeProfileDto.FlavorNoteRequest flavorRequest(Long flavorNoteId, int intensity) {
        CoffeeProfileDto.FlavorNoteRequest request = new CoffeeProfileDto.FlavorNoteRequest();
        ReflectionTestUtils.setField(request, "flavorNoteId", flavorNoteId);
        ReflectionTestUtils.setField(request, "intensity", (short) intensity);
        return request;
    }

    private CoffeeProfileDto.BrewMethodRequest brewRequest(Long brewMethodId, String note) {
        CoffeeProfileDto.BrewMethodRequest request = new CoffeeProfileDto.BrewMethodRequest();
        ReflectionTestUtils.setField(request, "brewMethodId", brewMethodId);
        ReflectionTestUtils.setField(request, "recommendationNote", note);
        return request;
    }

    private CoffeeProfileDto.VarietyRequest varietyRequest(Long coffeeVarietyId) {
        CoffeeProfileDto.VarietyRequest request = new CoffeeProfileDto.VarietyRequest();
        ReflectionTestUtils.setField(request, "coffeeVarietyId", coffeeVarietyId);
        return request;
    }

    private CoffeeProfileDto.ComponentRequest componentRequest(
            String countryCode,
            String region,
            Long processingMethodId,
            String ratio
    ) {
        CoffeeProfileDto.ComponentRequest request = new CoffeeProfileDto.ComponentRequest();
        ReflectionTestUtils.setField(request, "originCountryCode", countryCode);
        ReflectionTestUtils.setField(request, "originRegion", region);
        ReflectionTestUtils.setField(request, "processingMethodId", processingMethodId);
        ReflectionTestUtils.setField(request, "componentRatio", new BigDecimal(ratio));
        return request;
    }
}

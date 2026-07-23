package com.back.coffeeprod.domain.coffeeprofile.service;

import com.back.coffeeprod.domain.coffeeprofile.dto.BrewMethodDto;
import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeVarietyDto;
import com.back.coffeeprod.domain.coffeeprofile.dto.FlavorNoteDto;
import com.back.coffeeprod.domain.coffeeprofile.repository.BrewMethodRepository;
import com.back.coffeeprod.domain.coffeeprofile.repository.CoffeeProfileRepository;
import com.back.coffeeprod.domain.coffeeprofile.repository.CoffeeVarietyRepository;
import com.back.coffeeprod.domain.coffeeprofile.repository.FlavorNoteRepository;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-metadata-test;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=test",
        "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key",
        "pg.toss.secret-key=test-secret-key"
})
@DisplayName("커피 카탈로그 메타데이터 서비스 통합 테스트")
class CoffeeCatalogMetadataServiceIntegrationTest {

    private final BrewMethodService brewMethodService;
    private final FlavorNoteService flavorNoteService;
    private final CoffeeVarietyService coffeeVarietyService;
    private final CoffeeProfileRepository coffeeProfileRepository;
    private final BrewMethodRepository brewMethodRepository;
    private final FlavorNoteRepository flavorNoteRepository;
    private final CoffeeVarietyRepository coffeeVarietyRepository;

    @Autowired
    CoffeeCatalogMetadataServiceIntegrationTest(
            BrewMethodService brewMethodService,
            FlavorNoteService flavorNoteService,
            CoffeeVarietyService coffeeVarietyService,
            CoffeeProfileRepository coffeeProfileRepository,
            BrewMethodRepository brewMethodRepository,
            FlavorNoteRepository flavorNoteRepository,
            CoffeeVarietyRepository coffeeVarietyRepository
    ) {
        this.brewMethodService = brewMethodService;
        this.flavorNoteService = flavorNoteService;
        this.coffeeVarietyService = coffeeVarietyService;
        this.coffeeProfileRepository = coffeeProfileRepository;
        this.brewMethodRepository = brewMethodRepository;
        this.flavorNoteRepository = flavorNoteRepository;
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
    @DisplayName("중복된 코드의 추출 방식을 등록할 수 없다")
    void createBrewMethod_rejectsDuplicateCode() {
        brewMethodService.createBrewMethod(brewCreateRequest("V60", "V60", "Pour over"));

        CustomException exception = assertThrows(CustomException.class, () ->
                brewMethodService.createBrewMethod(
                        brewCreateRequest("V60", "V60 Dripper", "Duplicate code")
                )
        );

        assertEquals(ErrorCode.DUPLICATE_BREW_METHOD_CODE, exception.getErrorCode());
    }

    @Test
    @DisplayName("추출 방식 수정 시 코드는 유지하고 표시 정보를 변경한다")
    void updateBrewMethod_keepsCodeAndChangesDisplayInformation() {
        BrewMethodDto.Response created = brewMethodService.createBrewMethod(
                brewCreateRequest("AEROPRESS", "Aeropress", "Original")
        );

        BrewMethodDto.Response response = brewMethodService.updateBrewMethod(
                created.getId(),
                brewUpdateRequest("AeroPress", "Updated")
        );

        assertEquals("AEROPRESS", response.getCode());
        assertEquals("AeroPress", response.getName());
        assertEquals("Updated", response.getDescription());
    }

    @Test
    @DisplayName("추출 방식 목록을 이름순으로 조회한다")
    void getBrewMethods_returnsMethodsOrderedByName() {
        brewMethodService.createBrewMethod(brewCreateRequest("V60", "V60", null));
        brewMethodService.createBrewMethod(brewCreateRequest("AEROPRESS", "AeroPress", null));

        List<BrewMethodDto.Response> response = brewMethodService.getBrewMethods();

        assertEquals(List.of("AeroPress", "V60"), response.stream().map(BrewMethodDto.Response::getName).toList());
    }

    @Test
    @DisplayName("중복된 코드의 향미 노트를 등록할 수 없다")
    void createFlavorNote_rejectsDuplicateCode() {
        flavorNoteService.createFlavorNote(flavorCreateRequest("CITRUS", "Citrus", "Bright citrus"));

        CustomException exception = assertThrows(CustomException.class, () ->
                flavorNoteService.createFlavorNote(
                        flavorCreateRequest("CITRUS", "Lemon", "Duplicate code")
                )
        );

        assertEquals(ErrorCode.DUPLICATE_FLAVOR_NOTE_CODE, exception.getErrorCode());
    }

    @Test
    @DisplayName("향미 노트 수정 시 코드는 유지하고 표시 정보를 변경한다")
    void updateFlavorNote_keepsCodeAndChangesDisplayInformation() {
        FlavorNoteDto.Response created = flavorNoteService.createFlavorNote(
                flavorCreateRequest("CHOCOLATE", "Chocolate", "Original")
        );

        FlavorNoteDto.Response response = flavorNoteService.updateFlavorNote(
                created.getId(),
                flavorUpdateRequest("Dark Chocolate", "Updated")
        );

        assertEquals("CHOCOLATE", response.getCode());
        assertEquals("Dark Chocolate", response.getName());
        assertEquals("Updated", response.getDescription());
    }

    @Test
    @DisplayName("향미 노트 목록을 이름순으로 조회한다")
    void getFlavorNotes_returnsNotesOrderedByName() {
        flavorNoteService.createFlavorNote(flavorCreateRequest("NUTTY", "Nutty", null));
        flavorNoteService.createFlavorNote(flavorCreateRequest("BERRY", "Berry", null));

        List<FlavorNoteDto.Response> response = flavorNoteService.getFlavorNotes();

        assertEquals(List.of("Berry", "Nutty"), response.stream().map(FlavorNoteDto.Response::getName).toList());
    }

    @Test
    @DisplayName("중복된 코드의 커피 품종을 등록할 수 없다")
    void createCoffeeVariety_rejectsDuplicateCode() {
        coffeeVarietyService.createCoffeeVariety(
                varietyCreateRequest("BOURBON", "Bourbon", "Sweet variety")
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                coffeeVarietyService.createCoffeeVariety(
                        varietyCreateRequest("BOURBON", "Red Bourbon", "Duplicate code")
                )
        );

        assertEquals(ErrorCode.DUPLICATE_COFFEE_VARIETY_CODE, exception.getErrorCode());
    }

    @Test
    @DisplayName("커피 품종 수정 시 코드는 유지하고 표시 정보를 변경한다")
    void updateCoffeeVariety_keepsCodeAndChangesDisplayInformation() {
        CoffeeVarietyDto.Response created = coffeeVarietyService.createCoffeeVariety(
                varietyCreateRequest("GEISHA", "Geisha", "Original")
        );

        CoffeeVarietyDto.Response response = coffeeVarietyService.updateCoffeeVariety(
                created.getId(),
                varietyUpdateRequest("Gesha", "Updated")
        );

        assertEquals("GEISHA", response.getCode());
        assertEquals("Gesha", response.getName());
        assertEquals("Updated", response.getDescription());
    }

    @Test
    @DisplayName("커피 품종 목록을 이름순으로 조회한다")
    void getCoffeeVarieties_returnsVarietiesOrderedByName() {
        coffeeVarietyService.createCoffeeVariety(varietyCreateRequest("CATURRA", "Caturra", null));
        coffeeVarietyService.createCoffeeVariety(varietyCreateRequest("BOURBON", "Bourbon", null));

        List<CoffeeVarietyDto.Response> response = coffeeVarietyService.getCoffeeVarieties();

        assertEquals(List.of("Bourbon", "Caturra"), response.stream().map(CoffeeVarietyDto.Response::getName).toList());
    }

    private void cleanUp() {
        coffeeProfileRepository.deleteAll();
        brewMethodRepository.deleteAll();
        flavorNoteRepository.deleteAll();
        coffeeVarietyRepository.deleteAll();
    }

    private BrewMethodDto.CreateRequest brewCreateRequest(String code, String name, String description) {
        BrewMethodDto.CreateRequest request = new BrewMethodDto.CreateRequest();
        ReflectionTestUtils.setField(request, "code", code);
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "description", description);
        return request;
    }

    private BrewMethodDto.UpdateRequest brewUpdateRequest(String name, String description) {
        BrewMethodDto.UpdateRequest request = new BrewMethodDto.UpdateRequest();
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "description", description);
        return request;
    }

    private FlavorNoteDto.CreateRequest flavorCreateRequest(String code, String name, String description) {
        FlavorNoteDto.CreateRequest request = new FlavorNoteDto.CreateRequest();
        ReflectionTestUtils.setField(request, "code", code);
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "description", description);
        return request;
    }

    private FlavorNoteDto.UpdateRequest flavorUpdateRequest(String name, String description) {
        FlavorNoteDto.UpdateRequest request = new FlavorNoteDto.UpdateRequest();
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "description", description);
        return request;
    }

    private CoffeeVarietyDto.CreateRequest varietyCreateRequest(String code, String name, String description) {
        CoffeeVarietyDto.CreateRequest request = new CoffeeVarietyDto.CreateRequest();
        ReflectionTestUtils.setField(request, "code", code);
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "description", description);
        return request;
    }

    private CoffeeVarietyDto.UpdateRequest varietyUpdateRequest(String name, String description) {
        CoffeeVarietyDto.UpdateRequest request = new CoffeeVarietyDto.UpdateRequest();
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "description", description);
        return request;
    }
}

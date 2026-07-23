package com.back.coffeeprod.domain.recommendation.service;

import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import com.back.coffeeprod.domain.coffeeprofile.repository.ProcessingMethodRepository;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.recommendation.dto.MemberCoffeePreferenceDto;
import com.back.coffeeprod.domain.recommendation.repository.MemberCoffeePreferenceRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:member-preference-test;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=test",
        "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key",
        "pg.toss.secret-key=test-secret-key"
})
@DisplayName("회원 커피 선호도 서비스 통합 테스트")
class MemberCoffeePreferenceServiceIntegrationTest {

    private final MemberCoffeePreferenceService preferenceService;
    private final MemberCoffeePreferenceRepository preferenceRepository;
    private final ProcessingMethodRepository processingMethodRepository;
    private final MemberRepository memberRepository;

    @Autowired
    MemberCoffeePreferenceServiceIntegrationTest(
            MemberCoffeePreferenceService preferenceService,
            MemberCoffeePreferenceRepository preferenceRepository,
            ProcessingMethodRepository processingMethodRepository,
            MemberRepository memberRepository
    ) {
        this.preferenceService = preferenceService;
        this.preferenceRepository = preferenceRepository;
        this.processingMethodRepository = processingMethodRepository;
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
    @DisplayName("회원의 가공 방식 선호도를 포함한 커피 선호 정보를 생성한다")
    void upsertMyPreference_createsPreferenceWithProcessingMethod() {
        Member member = saveMember();
        ProcessingMethod processingMethod = saveProcessingMethod("WASHED", "Washed");

        MemberCoffeePreferenceDto.Response response = preferenceService.upsertMyPreference(
                member.getId(),
                preferenceRequest(
                        processingMethod.getId(),
                        BeanType.SINGLE_ORIGIN,
                        RoastLevel.LIGHT,
                        false,
                        (short) 5,
                        (short) 2,
                        (short) 4,
                        (short) 5
                )
        );

        assertEquals(1, preferenceRepository.count());
        assertEquals("WASHED", response.getProcessingMethod().getCode());
        assertEquals(BeanType.SINGLE_ORIGIN, response.getBeanType());
        assertEquals(RoastLevel.LIGHT, response.getRoastLevel());
        assertEquals((short) 5, response.getPreferredAcidity());
    }

    @Test
    @DisplayName("기존 커피 선호 정보를 수정하고 생략된 선택값을 초기화한다")
    void upsertMyPreference_updatesExistingRowAndClearsOmittedValues() {
        Member member = saveMember();
        ProcessingMethod processingMethod = saveProcessingMethod("NATURAL", "Natural");
        MemberCoffeePreferenceDto.Response created = preferenceService.upsertMyPreference(
                member.getId(),
                preferenceRequest(
                        processingMethod.getId(),
                        BeanType.SINGLE_ORIGIN,
                        RoastLevel.LIGHT,
                        false,
                        (short) 5,
                        null,
                        null,
                        null
                )
        );

        MemberCoffeePreferenceDto.Response updated = preferenceService.upsertMyPreference(
                member.getId(),
                preferenceRequest(
                        null,
                        BeanType.BLEND,
                        RoastLevel.DARK,
                        null,
                        null,
                        (short) 5,
                        (short) 4,
                        null
                )
        );

        assertEquals(created.getId(), updated.getId());
        assertEquals(1, preferenceRepository.count());
        assertNull(updated.getProcessingMethod());
        assertNull(updated.getDecaf());
        assertNull(updated.getPreferredAcidity());
        assertEquals((short) 5, updated.getPreferredBody());
        assertEquals(BeanType.BLEND, updated.getBeanType());
    }

    @Test
    @DisplayName("선호 조건이 하나도 없는 요청은 저장할 수 없다")
    void upsertMyPreference_rejectsRequestWithoutAnyPreference() {
        Member member = saveMember();

        CustomException exception = assertThrows(CustomException.class, () ->
                preferenceService.upsertMyPreference(member.getId(), new MemberCoffeePreferenceDto.Request())
        );

        assertEquals(ErrorCode.COFFEE_PREFERENCE_REQUIRED, exception.getErrorCode());
        assertEquals(0, preferenceRepository.count());
    }

    @Test
    @DisplayName("존재하지 않는 가공 방식은 선호 정보로 저장할 수 없다")
    void upsertMyPreference_rejectsUnknownProcessingMethod() {
        Member member = saveMember();
        MemberCoffeePreferenceDto.Request request = preferenceRequest(
                999_999L,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        CustomException exception = assertThrows(CustomException.class, () ->
                preferenceService.upsertMyPreference(member.getId(), request)
        );

        assertEquals(ErrorCode.PROCESSING_METHOD_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    @DisplayName("저장된 선호 정보가 없는 회원은 선호도를 조회할 수 없다")
    void getMyPreference_rejectsMemberWithoutSavedPreference() {
        Member member = saveMember();

        CustomException exception = assertThrows(CustomException.class, () ->
                preferenceService.getMyPreference(member.getId())
        );

        assertEquals(ErrorCode.COFFEE_PREFERENCE_NOT_FOUND, exception.getErrorCode());
    }

    private void cleanUp() {
        preferenceRepository.deleteAll();
        processingMethodRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private Member saveMember() {
        return memberRepository.save(Member.builder()
                .email("preference@test.com")
                .password("encoded-password")
                .name("Preference Tester")
                .nickname("preferenceTester")
                .role(Role.USER)
                .build());
    }

    private ProcessingMethod saveProcessingMethod(String code, String name) {
        return processingMethodRepository.save(ProcessingMethod.builder()
                .code(code)
                .name(name)
                .description(name + " processing")
                .build());
    }

    private MemberCoffeePreferenceDto.Request preferenceRequest(
            Long processingMethodId,
            BeanType beanType,
            RoastLevel roastLevel,
            Boolean decaf,
            Short acidity,
            Short body,
            Short sweetness,
            Short aroma
    ) {
        MemberCoffeePreferenceDto.Request request = new MemberCoffeePreferenceDto.Request();
        ReflectionTestUtils.setField(request, "processingMethodId", processingMethodId);
        ReflectionTestUtils.setField(request, "beanType", beanType);
        ReflectionTestUtils.setField(request, "roastLevel", roastLevel);
        ReflectionTestUtils.setField(request, "decaf", decaf);
        ReflectionTestUtils.setField(request, "preferredAcidity", acidity);
        ReflectionTestUtils.setField(request, "preferredBody", body);
        ReflectionTestUtils.setField(request, "preferredSweetness", sweetness);
        ReflectionTestUtils.setField(request, "preferredAroma", aroma);
        return request;
    }
}

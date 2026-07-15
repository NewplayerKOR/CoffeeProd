package com.back.coffeeprod.domain.coffeeprofile.service;

import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeProfileDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.entity.BrewMethod;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfileBrewMethod;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfileFlavorNote;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfileVariety;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeVariety;
import com.back.coffeeprod.domain.coffeeprofile.entity.FlavorNote;
import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import com.back.coffeeprod.domain.coffeeprofile.repository.CoffeeProfileRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoffeeProfileService {

    private static final int MAX_FLAVOR_NOTE_COUNT = 5;
    private static final int MAX_BREW_METHOD_COUNT = 3;
    private static final int MAX_VARIETY_COUNT = 3;

    private final CoffeeProfileRepository coffeeProfileRepository;
    private final ProcessingMethodService processingMethodService;
    private final FlavorNoteService flavorNoteService;
    private final BrewMethodService brewMethodService;
    private final CoffeeVarietyService coffeeVarietyService;

    // 커피 프로필 목록을 조회함
    public Page<CoffeeProfileDto.Response> getCoffeeProfiles(Pageable pageable) {
        return coffeeProfileRepository.findAll(pageable)
                .map(CoffeeProfileDto.Response::new);
    }

    // 커피 프로필 상세를 조회함
    public CoffeeProfileDto.Response getCoffeeProfile(Long coffeeProfileId) {
        return new CoffeeProfileDto.Response(findCoffeeProfileById(coffeeProfileId));
    }

    // 커피 프로필을 등록함
    @Transactional
    public CoffeeProfileDto.Response createCoffeeProfile(CoffeeProfileDto.Request request) {
        validateCoffeeProfileRequest(request);

        ProcessingMethod processingMethod = resolveProcessingMethod(request.getProcessingMethodId());

        CoffeeProfile coffeeProfile = CoffeeProfile.builder()
                .processingMethod(processingMethod)
                .profileName(request.getProfileName())
                .beanType(request.getBeanType())
                .originCountryCode(request.getOriginCountryCode())
                .originRegion(request.getOriginRegion())
                .farmOrCooperative(request.getFarmOrCooperative())
                .producer(request.getProducer())
                .altitudeMin(request.getAltitudeMin())
                .altitudeMax(request.getAltitudeMax())
                .roastLevel(request.getRoastLevel())
                .decaf(request.getDecaf())
                .decafMethod(request.getDecafMethod())
                .acidity(request.getAcidity())
                .body(request.getBody())
                .sweetness(request.getSweetness())
                .aroma(request.getAroma())
                .summary(request.getSummary())
                .build();

        replaceCoffeeProfileContents(coffeeProfile, request);

        return new CoffeeProfileDto.Response(coffeeProfileRepository.save(coffeeProfile));
    }

    // 커피 프로필을 수정함
    @Transactional
    public CoffeeProfileDto.Response updateCoffeeProfile(
            Long coffeeProfileId,
            CoffeeProfileDto.Request request
    ) {
        validateCoffeeProfileRequest(request);

        CoffeeProfile coffeeProfile = findCoffeeProfileById(coffeeProfileId);
        ProcessingMethod processingMethod = resolveProcessingMethod(request.getProcessingMethodId());

        coffeeProfile.update(
                processingMethod,
                request.getProfileName(),
                request.getBeanType(),
                request.getOriginCountryCode(),
                request.getOriginRegion(),
                request.getFarmOrCooperative(),
                request.getProducer(),
                request.getAltitudeMin(),
                request.getAltitudeMax(),
                request.getRoastLevel(),
                request.getDecaf(),
                request.getDecafMethod(),
                request.getAcidity(),
                request.getBody(),
                request.getSweetness(),
                request.getAroma(),
                request.getSummary()
        );

        replaceCoffeeProfileContents(coffeeProfile, request);

        return new CoffeeProfileDto.Response(coffeeProfile);
    }

    // 내부 공용 조회
    public CoffeeProfile findCoffeeProfileById(Long coffeeProfileId) {
        return coffeeProfileRepository.findById(coffeeProfileId)
                .orElseThrow(() -> new CustomException(ErrorCode.COFFEE_PROFILE_NOT_FOUND));
    }

    private ProcessingMethod resolveProcessingMethod(Long processingMethodId) {
        if (processingMethodId == null) {
            return null;
        }

        return processingMethodService.findProcessingMethodById(processingMethodId);
    }

    private void validateCoffeeProfileRequest(CoffeeProfileDto.Request request) {
        if (request.getFlavorNotes() == null
                || request.getBrewMethods() == null
                || request.getVarieties() == null) {
            throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
        }

        if (request.getBeanType() == BeanType.SINGLE_ORIGIN
                && request.getOriginCountryCode() == null) {
            throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
        }

        if (request.getBeanType() == BeanType.BLEND
                && request.getOriginCountryCode() != null) {
            throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
        }

        if (!Boolean.TRUE.equals(request.getDecaf())
                && request.getDecafMethod() != null) {
            throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
        }

        if (request.getAltitudeMax() != null
                && request.getAltitudeMin() == null) {
            throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
        }

        if (request.getAltitudeMin() != null
                && request.getAltitudeMax() != null
                && request.getAltitudeMin() > request.getAltitudeMax()) {
            throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
        }

        validateFlavorNoteRequests(request.getFlavorNotes());
        validateBrewMethodRequests(request.getBrewMethods());
        validateVarietyRequests(request.getVarieties());
    }

    // 프로필의 향미와 추출법 연결 정보를 전체 교체함
    private void replaceCoffeeProfileContents(
            CoffeeProfile coffeeProfile,
            CoffeeProfileDto.Request request
    ) {
        List<CoffeeProfileFlavorNote> flavorNotes = new ArrayList<>();

        for (int index = 0; index < request.getFlavorNotes().size(); index++) {
            CoffeeProfileDto.FlavorNoteRequest flavorNoteRequest =
                    request.getFlavorNotes().get(index);
            FlavorNote flavorNote = flavorNoteService.findFlavorNoteById(
                    flavorNoteRequest.getFlavorNoteId()
            );

            flavorNotes.add(CoffeeProfileFlavorNote.of(
                    coffeeProfile,
                    flavorNote,
                    (short) (index + 1),
                    flavorNoteRequest.getIntensity()
            ));
        }

        List<CoffeeProfileBrewMethod> brewMethods = new ArrayList<>();

        for (int index = 0; index < request.getBrewMethods().size(); index++) {
            CoffeeProfileDto.BrewMethodRequest brewMethodRequest =
                    request.getBrewMethods().get(index);
            BrewMethod brewMethod = brewMethodService.findBrewMethodById(
                    brewMethodRequest.getBrewMethodId()
            );

            brewMethods.add(CoffeeProfileBrewMethod.of(
                    coffeeProfile,
                    brewMethod,
                    (short) (index + 1),
                    brewMethodRequest.getRecommendationNote()
            ));
        }

        List<CoffeeProfileVariety> varieties = new ArrayList<>();

        for (int index = 0; index < request.getVarieties().size(); index++) {
            CoffeeProfileDto.VarietyRequest varietyRequest =
                    request.getVarieties().get(index);
            CoffeeVariety coffeeVariety =
                    coffeeVarietyService.findCoffeeVarietyById(
                            varietyRequest.getCoffeeVarietyId()
                    );

            varieties.add(CoffeeProfileVariety.of(
                    coffeeProfile,
                    coffeeVariety,
                    (short) (index + 1)
            ));
        }

        coffeeProfile.replaceFlavorNotes(flavorNotes);
        coffeeProfile.replaceBrewMethods(brewMethods);
        coffeeProfile.replaceVarieties(varieties);
    }

    // 향미 노트 입력값과 중복을 검증함
    private void validateFlavorNoteRequests(
            List<CoffeeProfileDto.FlavorNoteRequest> flavorNotes
    ) {
        if (flavorNotes.size() > MAX_FLAVOR_NOTE_COUNT) {
            throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
        }

        Set<Long> flavorNoteIds = new HashSet<>();

        for (CoffeeProfileDto.FlavorNoteRequest flavorNote : flavorNotes) {
            if (flavorNote == null) {
                throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
            }

            Short intensity = flavorNote.getIntensity();

            if (flavorNote.getFlavorNoteId() == null
                    || intensity == null
                    || intensity < 1
                    || intensity > 5) {
                throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
            }

            if (!flavorNoteIds.add(flavorNote.getFlavorNoteId())) {
                throw new CustomException(
                        ErrorCode.DUPLICATE_COFFEE_PROFILE_FLAVOR_NOTE
                );
            }
        }
    }

    // 추천 추출법 입력값과 중복을 검증함
    private void validateBrewMethodRequests(
            List<CoffeeProfileDto.BrewMethodRequest> brewMethods
    ) {
        if (brewMethods.size() > MAX_BREW_METHOD_COUNT) {
            throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
        }

        Set<Long> brewMethodIds = new HashSet<>();

        for (CoffeeProfileDto.BrewMethodRequest brewMethod : brewMethods) {
            if (brewMethod == null
                    || brewMethod.getBrewMethodId() == null
                    || (brewMethod.getRecommendationNote() != null
                    && brewMethod.getRecommendationNote().length() > 500)) {
                throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
            }

            if (!brewMethodIds.add(brewMethod.getBrewMethodId())) {
                throw new CustomException(
                        ErrorCode.DUPLICATE_COFFEE_PROFILE_BREW_METHOD
                );
            }
        }
    }

    // 커피 품종 입력값과 중복을 검증함
    private void validateVarietyRequests(
            List<CoffeeProfileDto.VarietyRequest> varieties
    ) {
        if (varieties.size() > MAX_VARIETY_COUNT) {
            throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
        }

        Set<Long> coffeeVarietyIds = new HashSet<>();

        for (CoffeeProfileDto.VarietyRequest variety : varieties) {
            if (variety == null || variety.getCoffeeVarietyId() == null) {
                throw new CustomException(ErrorCode.INVALID_COFFEE_PROFILE);
            }

            if (!coffeeVarietyIds.add(variety.getCoffeeVarietyId())) {
                throw new CustomException(
                        ErrorCode.DUPLICATE_COFFEE_PROFILE_VARIETY
                );
            }
        }
    }
}

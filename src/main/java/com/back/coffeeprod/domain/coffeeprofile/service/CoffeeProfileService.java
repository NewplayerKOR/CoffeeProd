package com.back.coffeeprod.domain.coffeeprofile.service;

import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeProfileDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import com.back.coffeeprod.domain.coffeeprofile.repository.CoffeeProfileRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoffeeProfileService {

    private final CoffeeProfileRepository coffeeProfileRepository;
    private final ProcessingMethodService processingMethodService;

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
    }
}

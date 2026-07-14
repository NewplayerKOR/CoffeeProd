package com.back.coffeeprod.domain.recommendation.service;

import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import com.back.coffeeprod.domain.coffeeprofile.service.ProcessingMethodService;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.domain.recommendation.dto.MemberCoffeePreferenceDto;
import com.back.coffeeprod.domain.recommendation.entity.MemberCoffeePreference;
import com.back.coffeeprod.domain.recommendation.repository.MemberCoffeePreferenceRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberCoffeePreferenceService {

    private final MemberCoffeePreferenceRepository memberCoffeePreferenceRepository;
    private final MemberService memberService;
    private final ProcessingMethodService processingMethodService;

    // 내 커피 취향을 조회함
    public MemberCoffeePreferenceDto.Response getMyPreference(Long memberId) {
        return new MemberCoffeePreferenceDto.Response(
                getPreferenceEntity(memberId)
        );
    }

    // 개인화 추천에 사용할 취향을 조회함
    public MemberCoffeePreference getPreferenceEntity(Long memberId) {
        return memberCoffeePreferenceRepository.findByMemberId(memberId)
                .orElseThrow(() -> new CustomException(
                        ErrorCode.COFFEE_PREFERENCE_NOT_FOUND
                ));
    }

    // 내 커피 취향을 생성 또는 전체 수정함
    @Transactional
    public MemberCoffeePreferenceDto.Response upsertMyPreference(
            Long memberId,
            MemberCoffeePreferenceDto.Request request
    ) {
        validateRequest(request);

        ProcessingMethod processingMethod = resolveProcessingMethod(
                request.getProcessingMethodId()
        );

        return memberCoffeePreferenceRepository.findByMemberId(memberId)
                .map(preference -> {
                    preference.update(
                            processingMethod,
                            request.getBeanType(),
                            request.getRoastLevel(),
                            request.getDecaf(),
                            request.getPreferredAcidity(),
                            request.getPreferredBody(),
                            request.getPreferredSweetness(),
                            request.getPreferredAroma()
                    );

                    return new MemberCoffeePreferenceDto.Response(preference);
                })
                .orElseGet(() -> createPreference(
                        memberId,
                        processingMethod,
                        request
                ));
    }

    // 신규 회원 취향을 저장함
    private MemberCoffeePreferenceDto.Response createPreference(
            Long memberId,
            ProcessingMethod processingMethod,
            MemberCoffeePreferenceDto.Request request
    ) {
        Member member = memberService.findMemberById(memberId);

        MemberCoffeePreference preference = MemberCoffeePreference.builder()
                .member(member)
                .processingMethod(processingMethod)
                .beanType(request.getBeanType())
                .roastLevel(request.getRoastLevel())
                .decaf(request.getDecaf())
                .preferredAcidity(request.getPreferredAcidity())
                .preferredBody(request.getPreferredBody())
                .preferredSweetness(request.getPreferredSweetness())
                .preferredAroma(request.getPreferredAroma())
                .build();

        return new MemberCoffeePreferenceDto.Response(
                memberCoffeePreferenceRepository.save(preference)
        );
    }

    // 가공 방식 ID를 엔티티로 변환함
    private ProcessingMethod resolveProcessingMethod(
            Long processingMethodId
    ) {
        if (processingMethodId == null) {
            return null;
        }

        return processingMethodService.findProcessingMethodById(
                processingMethodId
        );
    }

    // 최소 하나의 취향 조건을 검증함
    private void validateRequest(MemberCoffeePreferenceDto.Request request) {
        boolean hasPreference =
                request.getProcessingMethodId() != null
                        || request.getBeanType() != null
                        || request.getRoastLevel() != null
                        || request.getDecaf() != null
                        || request.getPreferredAcidity() != null
                        || request.getPreferredBody() != null
                        || request.getPreferredSweetness() != null
                        || request.getPreferredAroma() != null;

        if (!hasPreference) {
            throw new CustomException(
                    ErrorCode.COFFEE_PREFERENCE_REQUIRED
            );
        }
    }
}

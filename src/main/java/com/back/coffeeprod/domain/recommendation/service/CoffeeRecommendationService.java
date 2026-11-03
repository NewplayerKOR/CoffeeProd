package com.back.coffeeprod.domain.recommendation.service;

import com.back.coffeeprod.domain.coffeeprofile.service.ProcessingMethodService;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.product.repository.RecommendationCandidateRow;
import com.back.coffeeprod.domain.recommendation.dto.CoffeeRecommendationDto;
import com.back.coffeeprod.domain.recommendation.entity.MemberCoffeePreference;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CoffeeRecommendationService {

    private final ProductRepository productRepository;
    private final ProcessingMethodService processingMethodService;
    private final MemberCoffeePreferenceService memberCoffeePreferenceService;

    // 취향 조건으로 판매 가능한 커피를 추천함
    public List<CoffeeRecommendationDto.Response> recommend(
            CoffeeRecommendationDto.Request request
    ) {
        validateRequest(request);

        var selected = productRepository
                .findRecommendationCandidates(
                        ProductStatus.ON_SALE,
                        request.getDecaf()
                )
                .stream()
                .map(product -> createCandidate(product, request))
                .sorted(
                        Comparator.comparingInt(RecommendationCandidate::score)
                                .reversed()
                                .thenComparing(candidate -> candidate.product().price())
                                .thenComparing(candidate -> candidate.product().productId())
                )
                .limit(resolveLimit(request.getLimit()))
                .toList();

        if (selected.isEmpty()) {
            return List.of();
        }

        var productIds = selected.stream().map(candidate -> candidate.product().productId()).toList();
        var productsById = productRepository.findRecommendationDetailsByIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        // IN 조회의 반환 순서 대신 점수·가격·ID로 선정한 순서를 사용한다.
        return selected.stream()
                .map(candidate -> new CoffeeRecommendationDto.Response(
                        productsById.get(candidate.product().productId()),
                        candidate.score(),
                        candidate.reasons()
                ))
                .toList();
    }

    // 저장된 회원 취향으로 커피를 추천함
    public List<CoffeeRecommendationDto.Response> recommendForMember(
            Long memberId,
            Integer limit
    ) {
        MemberCoffeePreference preference =
                memberCoffeePreferenceService.getPreferenceEntity(memberId);

        return recommend(CoffeeRecommendationDto.Request.from(
                preference,
                limit
        ));
    }

    // 추천 조건을 검증함
    private void validateRequest(CoffeeRecommendationDto.Request request) {
        if (request.getProcessingMethodId() != null) {
            processingMethodService.findProcessingMethodById(
                    request.getProcessingMethodId()
            );
        }

        boolean hasPreference =
                request.getRoastLevel() != null
                        || request.getBeanType() != null
                        || request.getProcessingMethodId() != null
                        || request.getDecaf() != null
                        || request.getPreferredAcidity() != null
                        || request.getPreferredBody() != null
                        || request.getPreferredSweetness() != null
                        || request.getPreferredAroma() != null;

        if (!hasPreference) {
            throw new CustomException(
                    ErrorCode.RECOMMENDATION_PREFERENCE_REQUIRED
            );
        }
    }

    // 추천 후보와 점수를 생성함
    private RecommendationCandidate createCandidate(
            RecommendationCandidateRow product,
            CoffeeRecommendationDto.Request request
    ) {
        List<String> reasons = new ArrayList<>();
        int score = 0;

        if (request.getDecaf() != null) {
            score += 1;
            reasons.add("디카페인 여부 조건 충족");
        }

        score += calculateRoastScore(
                request.getRoastLevel(),
                product,
                reasons
        );

        score += calculateBeanTypeScore(
                request,
                product,
                reasons
        );

        score += calculateProcessingMethodScore(
                request,
                product,
                reasons
        );

        score += calculateSensoryScore(
                request.getPreferredAcidity(),
                product.acidity(),
                "산미",
                reasons
        );

        score += calculateSensoryScore(
                request.getPreferredBody(),
                product.body(),
                "바디",
                reasons
        );

        score += calculateSensoryScore(
                request.getPreferredSweetness(),
                product.sweetness(),
                "단맛",
                reasons
        );

        score += calculateSensoryScore(
                request.getPreferredAroma(),
                product.aroma(),
                "향",
                reasons
        );

        return new RecommendationCandidate(
                product,
                score,
                List.copyOf(reasons)
        );
    }

    // 로스팅 단계 일치 점수를 계산함
    private int calculateRoastScore(
            com.back.coffeeprod.domain.product.entity.RoastLevel preferredRoastLevel,
            RecommendationCandidateRow product,
            List<String> reasons
    ) {
        if (preferredRoastLevel == null) {
            return 0;
        }

        if (preferredRoastLevel == product.roastLevel()) {
            reasons.add("선호 로스팅 단계와 일치");
            return 3;
        }

        return 0;
    }

    // 원두 유형 일치 점수를 계산함
    private int calculateBeanTypeScore(
            CoffeeRecommendationDto.Request request,
            RecommendationCandidateRow product,
            List<String> reasons
    ) {
        if (request.getBeanType() == null) {
            return 0;
        }

        if (request.getBeanType() == product.beanType()) {
            reasons.add("선호 원두 유형과 일치");
            return 3;
        }

        return 0;
    }

    // 가공 방식 일치 점수를 계산함
    private int calculateProcessingMethodScore(
            CoffeeRecommendationDto.Request request,
            RecommendationCandidateRow product,
            List<String> reasons
    ) {
        if (request.getProcessingMethodId() == null) {
            return 0;
        }

        if (request.getProcessingMethodId().equals(product.processingMethodId())) {
            reasons.add("선호 가공 방식과 일치");
            return 3;
        }

        return 0;
    }

    // 감각 점수 유사도를 계산함
    private int calculateSensoryScore(
            Integer preferredScore,
            short actualScore,
            String label,
            List<String> reasons
    ) {
        if (preferredScore == null) {
            return 0;
        }

        int distance = Math.abs(preferredScore - actualScore);

        if (distance == 0) {
            reasons.add(label + " 선호와 일치");
            return 4;
        }

        if (distance == 1) {
            reasons.add(label + " 선호와 유사");
            return 2;
        }

        return 0;
    }

    // 추천 개수를 검증하고 반환함
    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return 5;
        }

        if (limit < 1 || limit > 10) {
            throw new CustomException(
                    ErrorCode.INVALID_RECOMMENDATION_LIMIT
            );
        }

        return limit;
    }

    private record RecommendationCandidate(
            RecommendationCandidateRow product,
            int score,
            List<String> reasons
    ) {
    }
}

package com.back.coffeeprod.domain.recommendation.service;

import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import com.back.coffeeprod.domain.coffeeprofile.service.ProcessingMethodService;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.recommendation.dto.CoffeeRecommendationDto;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CoffeeRecommendationService {

    private final ProductRepository productRepository;
    private final ProcessingMethodService processingMethodService;

    // 취향 조건으로 판매 가능한 커피를 추천함
    public List<CoffeeRecommendationDto.Response> recommend(
            CoffeeRecommendationDto.Request request
    ) {
        validateRequest(request);

        return productRepository
                .findAvailableProductsForRecommendation(
                        ProductStatus.ON_SALE,
                        request.getDecaf()
                )
                .stream()
                .map(product -> createCandidate(product, request))
                .sorted(
                        Comparator.comparingInt(RecommendationCandidate::score)
                                .reversed()
                                .thenComparing(candidate -> candidate.product().getPrice())
                                .thenComparing(candidate -> candidate.product().getId())
                )
                .limit(resolveLimit(request.getLimit()))
                .map(candidate -> new CoffeeRecommendationDto.Response(
                        candidate.product(),
                        candidate.score(),
                        candidate.reasons()
                ))
                .toList();
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
            Product product,
            CoffeeRecommendationDto.Request request
    ) {
        CoffeeProfile coffeeProfile = product.getCoffeeProfile();
        List<String> reasons = new ArrayList<>();
        int score = 0;

        if (request.getDecaf() != null) {
            score += 1;
            reasons.add("디카페인 여부 조건 충족");
        }

        score += calculateRoastScore(
                request.getRoastLevel(),
                coffeeProfile,
                reasons
        );

        score += calculateBeanTypeScore(
                request,
                coffeeProfile,
                reasons
        );

        score += calculateProcessingMethodScore(
                request,
                coffeeProfile,
                reasons
        );

        score += calculateSensoryScore(
                request.getPreferredAcidity(),
                coffeeProfile.getAcidity(),
                "산미",
                reasons
        );

        score += calculateSensoryScore(
                request.getPreferredBody(),
                coffeeProfile.getBody(),
                "바디",
                reasons
        );

        score += calculateSensoryScore(
                request.getPreferredSweetness(),
                coffeeProfile.getSweetness(),
                "단맛",
                reasons
        );

        score += calculateSensoryScore(
                request.getPreferredAroma(),
                coffeeProfile.getAroma(),
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
            CoffeeProfile coffeeProfile,
            List<String> reasons
    ) {
        if (preferredRoastLevel == null) {
            return 0;
        }

        if (preferredRoastLevel == coffeeProfile.getRoastLevel()) {
            reasons.add("선호 로스팅 단계와 일치");
            return 3;
        }

        return 0;
    }

    // 원두 유형 일치 점수를 계산함
    private int calculateBeanTypeScore(
            CoffeeRecommendationDto.Request request,
            CoffeeProfile coffeeProfile,
            List<String> reasons
    ) {
        if (request.getBeanType() == null) {
            return 0;
        }

        if (request.getBeanType() == coffeeProfile.getBeanType()) {
            reasons.add("선호 원두 유형과 일치");
            return 3;
        }

        return 0;
    }

    // 가공 방식 일치 점수를 계산함
    private int calculateProcessingMethodScore(
            CoffeeRecommendationDto.Request request,
            CoffeeProfile coffeeProfile,
            List<String> reasons
    ) {
        if (request.getProcessingMethodId() == null) {
            return 0;
        }

        ProcessingMethod processingMethod = coffeeProfile.getProcessingMethod();

        if (processingMethod != null
                && request.getProcessingMethodId().equals(processingMethod.getId())) {
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

    // 기본 추천 개수를 반환함
    private int resolveLimit(Integer limit) {
        return limit == null ? 5 : limit;
    }

    private record RecommendationCandidate(
            Product product,
            int score,
            List<String> reasons
    ) {
    }
}

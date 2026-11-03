package com.back.coffeeprod.domain.product.repository;

import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.product.entity.RoastLevel;

// 점수 계산에는 상품·프로필 엔티티와 응답용 연관 데이터를 로딩하지 않는다.
public record RecommendationCandidateRow(
        Long productId,
        int price,
        RoastLevel roastLevel,
        BeanType beanType,
        Long processingMethodId,
        short acidity,
        short body,
        short sweetness,
        short aroma
) {
}

package com.back.coffeeprod.domain.recommendation.repository;

import com.back.coffeeprod.domain.recommendation.entity.MemberCoffeePreference;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberCoffeePreferenceRepository extends JpaRepository<MemberCoffeePreference, Long> {

    // 가공 방식 정보를 함께 조회함
    @EntityGraph(attributePaths = "processingMethod")
    Optional<MemberCoffeePreference> findByMemberId(Long memberId);
}

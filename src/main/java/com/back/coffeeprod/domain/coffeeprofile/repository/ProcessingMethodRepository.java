package com.back.coffeeprod.domain.coffeeprofile.repository;

import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessingMethodRepository extends JpaRepository<CoffeeProfile, Long> {

    // 가공 방식 코드의 중복 여부 확인
    boolean existsByCode(String code);

    // 가공 방식 코드로 기본정보를 조회함
    Optional<ProcessingMethod> findByCode(String code);
}

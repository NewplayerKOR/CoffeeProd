package com.back.coffeeprod.domain.coffeeprofile.repository;

import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeVariety;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoffeeVarietyRepository
        extends JpaRepository<CoffeeVariety, Long> {

    // 커피 품종 코드 중복 여부를 확인함
    boolean existsByCode(String code);
}

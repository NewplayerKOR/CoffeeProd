package com.back.coffeeprod.domain.coffeeprofile.repository;

import com.back.coffeeprod.domain.coffeeprofile.entity.BrewMethod;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrewMethodRepository extends JpaRepository<BrewMethod, Long> {

    // 추출법 코드 중복 여부를 확인함
    boolean existsByCode(String code);
}

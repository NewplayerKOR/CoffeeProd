package com.back.coffeeprod.domain.product.repository;

import com.back.coffeeprod.domain.product.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    // 카테고리명 중복 등록 방지
    boolean existsByName(String name);
}

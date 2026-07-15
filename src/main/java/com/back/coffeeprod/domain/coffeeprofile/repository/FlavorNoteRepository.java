package com.back.coffeeprod.domain.coffeeprofile.repository;

import com.back.coffeeprod.domain.coffeeprofile.entity.FlavorNote;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlavorNoteRepository extends JpaRepository<FlavorNote, Long> {

    // 향미 노트 코드 중복 여부를 확인함
    boolean existsByCode(String code);
}

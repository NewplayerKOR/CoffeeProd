package com.back.coffeeprod.domain.coffeeprofile.repository;

import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoffeeProfileRepository extends JpaRepository<CoffeeProfile, Long> {
}

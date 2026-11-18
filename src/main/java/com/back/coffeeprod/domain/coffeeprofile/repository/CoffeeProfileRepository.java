package com.back.coffeeprod.domain.coffeeprofile.repository;

import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CoffeeProfileRepository extends JpaRepository<CoffeeProfile, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from CoffeeProfile profile where profile.id = :id")
    Optional<CoffeeProfile> findForUpdate(@Param("id") Long id);
}

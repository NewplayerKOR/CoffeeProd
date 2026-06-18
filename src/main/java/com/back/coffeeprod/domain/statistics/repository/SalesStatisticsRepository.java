package com.back.coffeeprod.domain.statistics.repository;

import com.back.coffeeprod.domain.statistics.entity.SalesStatistics;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SalesStatisticsRepository extends JpaRepository<SalesStatistics, Long> {

    Optional<SalesStatistics> findByStatDate(LocalDate statDate);

    List<SalesStatistics> findByStatDateBetweenOrderByStatDateAsc(LocalDate from, LocalDate to);
}
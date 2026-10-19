package com.back.coffeeprod.domain.statistics.repository;

import com.back.coffeeprod.domain.statistics.entity.SalesRebuildRequest;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.time.LocalDate;
import java.util.List;

public interface SalesRebuildRequestRepository extends JpaRepository<SalesRebuildRequest, LocalDate> {
    @Query("select r.statDate from SalesRebuildRequest r order by r.statDate")
    List<LocalDate> findDates(Pageable page);
}

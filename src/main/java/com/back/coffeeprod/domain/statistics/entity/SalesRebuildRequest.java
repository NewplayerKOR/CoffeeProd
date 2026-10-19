package com.back.coffeeprod.domain.statistics.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.*;

@Entity @Getter @NoArgsConstructor
public class SalesRebuildRequest {
    @Id private LocalDate statDate;
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ") private Instant requestedAt;
    @Column(length = 80) private String lastErrorCode;
    public SalesRebuildRequest(LocalDate date) { statDate = date; touch(); }
    public void touch() { requestedAt = Instant.now(); lastErrorCode = null; }
    public void failed() { lastErrorCode = "SALES_REBUILD_FAILED"; }
}

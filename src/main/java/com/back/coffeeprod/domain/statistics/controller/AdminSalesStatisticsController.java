package com.back.coffeeprod.domain.statistics.controller;

import com.back.coffeeprod.domain.statistics.dto.SalesStatisticsDto;
import com.back.coffeeprod.domain.statistics.entity.SalesStatisticsUnit;
import com.back.coffeeprod.domain.statistics.service.SalesStatisticsService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Admin-statistics", description = "관리자 통계 API")
@SecurityRequirement(name = "jwtAuth")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/statistics")
@RequiredArgsConstructor
public class AdminSalesStatisticsController {

    private final SalesStatisticsService salesStatisticsService;

    @GetMapping("/sales")
    public ResponseEntity<CommonResponse<List<SalesStatisticsDto.Response>>> getSalesStatistics(
            @RequestParam SalesStatisticsUnit unit,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        List<SalesStatisticsDto.Response> response =
                salesStatisticsService.getSalesStatistics(from, to, unit);

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    @PostMapping("/sales/aggregate")
    public ResponseEntity<CommonResponse<Void>> aggregateSalesManually(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statDate
    ) {
        salesStatisticsService.aggregateDailySales(statDate);

        return ResponseEntity.ok(CommonResponse.success(200, "매출 집계가 완료되었습니다."));
    }
}
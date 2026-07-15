package com.back.coffeeprod.domain.coffeeprofile.controller;

import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeVarietyDto;
import com.back.coffeeprod.domain.coffeeprofile.service.CoffeeVarietyService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "CoffeeVariety", description = "커피 품종 조회 API")
@RestController
@RequestMapping("/api/v1/coffee-varieties")
@RequiredArgsConstructor
public class CoffeeVarietyController {

    private final CoffeeVarietyService coffeeVarietyService;

    // 커피 품종 목록을 조회함
    @Operation(summary = "커피 품종 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<CoffeeVarietyDto.Response>>>
    getCoffeeVarieties() {
        return ResponseEntity.ok(CommonResponse.success(
                coffeeVarietyService.getCoffeeVarieties()
        ));
    }
}

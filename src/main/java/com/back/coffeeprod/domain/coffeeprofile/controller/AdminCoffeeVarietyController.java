package com.back.coffeeprod.domain.coffeeprofile.controller;

import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeVarietyDto;
import com.back.coffeeprod.domain.coffeeprofile.service.CoffeeVarietyService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Admin-CoffeeVariety", description = "관리자 커피 품종 관리 API")
@SecurityRequirement(name = "jwtAuth")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/coffee-varieties")
@RequiredArgsConstructor
public class AdminCoffeeVarietyController {

    private final CoffeeVarietyService coffeeVarietyService;

    // 관리자가 커피 품종 목록을 조회함
    @Operation(summary = "관리자 커피 품종 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<CoffeeVarietyDto.Response>>>
    getCoffeeVarieties() {
        return ResponseEntity.ok(CommonResponse.success(
                coffeeVarietyService.getCoffeeVarieties()
        ));
    }

    // 관리자가 커피 품종을 등록함
    @Operation(summary = "커피 품종 등록")
    @PostMapping
    public ResponseEntity<CommonResponse<CoffeeVarietyDto.Response>>
    createCoffeeVariety(
            @Valid @RequestBody CoffeeVarietyDto.CreateRequest request
    ) {
        CoffeeVarietyDto.Response response =
                coffeeVarietyService.createCoffeeVariety(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.success(response));
    }

    // 관리자가 커피 품종을 수정함
    @Operation(summary = "커피 품종 수정")
    @PutMapping("/{coffeeVarietyId}")
    public ResponseEntity<CommonResponse<CoffeeVarietyDto.Response>>
    updateCoffeeVariety(
            @PathVariable Long coffeeVarietyId,
            @Valid @RequestBody CoffeeVarietyDto.UpdateRequest request
    ) {
        CoffeeVarietyDto.Response response =
                coffeeVarietyService.updateCoffeeVariety(
                        coffeeVarietyId,
                        request
                );

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

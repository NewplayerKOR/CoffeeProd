package com.back.coffeeprod.domain.coffeeprofile.controller;

import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeProfileDto;
import com.back.coffeeprod.domain.coffeeprofile.service.CoffeeProfileService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "CoffeeProfile", description = "커피 프로필 조회 API")
@RestController
@RequestMapping("/api/v1/coffee-profiles")
@RequiredArgsConstructor
public class CoffeeProfileController {

    private final CoffeeProfileService coffeeProfileService;

    // 커피 프로필 목록을 조회함
    @Operation(summary = "커피 프로필 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<Page<CoffeeProfileDto.Response>>> getCoffeeProfiles(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        Page<CoffeeProfileDto.Response> response = coffeeProfileService.getCoffeeProfiles(pageable);
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 커피 프로필 상세를 조회함
    @Operation(summary = "커피 프로필 상세 조회")
    @GetMapping("/{coffeeProfileId}")
    public ResponseEntity<CommonResponse<CoffeeProfileDto.Response>> getCoffeeProfile(
            @PathVariable Long coffeeProfileId
    ) {
        CoffeeProfileDto.Response response = coffeeProfileService.getCoffeeProfile(coffeeProfileId);
        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

package com.back.coffeeprod.domain.coffeeprofile.controller;

import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeProfileDto;
import com.back.coffeeprod.domain.coffeeprofile.service.CoffeeProfileService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin-CoffeeProfile", description = "관리자 커피 프로필 관리 API")
@SecurityRequirement(name = "jwtAuth")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/coffee-profiles")
@RequiredArgsConstructor
public class AdminCoffeeProfileController {

    private final CoffeeProfileService coffeeProfileService;

    // 관리자가 커피 프로필 목록을 조회함
    @Operation(summary = "관리자가 커피 프로필 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<Page<CoffeeProfileDto.Response>>> getCoffeeProfiles(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        Page<CoffeeProfileDto.Response> response = coffeeProfileService.getCoffeeProfiles(pageable);
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 관리자가 커피 프로필 상세를 조회함
    @Operation(summary = "관리자 커피 프로필 상세 조회")
    @GetMapping("/{coffeeProfileId}")
    public ResponseEntity<CommonResponse<CoffeeProfileDto.Response>> getCoffeeProfile(
            @PathVariable Long coffeeProfileId
    ) {
        CoffeeProfileDto.Response response = coffeeProfileService.getCoffeeProfile(coffeeProfileId);
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 커피 프로필을 등록함
    @Operation(summary = "커피 프로필을 등록")
    @PostMapping
    public ResponseEntity<CommonResponse<CoffeeProfileDto.Response>> createCoffeeProfile(
            @Valid @RequestBody CoffeeProfileDto.Request request
    ) {
        CoffeeProfileDto.Response response = coffeeProfileService.createCoffeeProfile(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.success(response));
    }

    // 커피 프로필을 수정함
    @Operation(summary = "커피 프로필 수정")
    @PutMapping("/{coffeeProfileId}")
    public ResponseEntity<CommonResponse<CoffeeProfileDto.Response>> updateCoffeeProfile(
            @PathVariable Long coffeeProfileId,
            @Valid @RequestBody CoffeeProfileDto.Request request
    ) {
        CoffeeProfileDto.Response response = coffeeProfileService.updateCoffeeProfile(coffeeProfileId, request);
        return ResponseEntity.ok(CommonResponse.success(response));
    }


}

package com.back.coffeeprod.domain.coffeeprofile.controller;

import com.back.coffeeprod.domain.coffeeprofile.dto.BrewMethodDto;
import com.back.coffeeprod.domain.coffeeprofile.service.BrewMethodService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin-BrewMethod", description = "관리자 추천 추출법 관리 API")
@SecurityRequirement(name = "jwtAuth")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/brew-methods")
@RequiredArgsConstructor
public class AdminBrewMethodController {

    private final BrewMethodService brewMethodService;

    // 관리자가 추출법 목록을 조회함
    @Operation(summary = "관리자 추천 추출법 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<BrewMethodDto.Response>>>
    getBrewMethods() {
        return ResponseEntity.ok(CommonResponse.success(
                brewMethodService.getBrewMethods()
        ));
    }

    // 관리자가 추출법을 등록함
    @Operation(summary = "추천 추출법 등록")
    @PostMapping
    public ResponseEntity<CommonResponse<BrewMethodDto.Response>>
    createBrewMethod(
            @Valid @RequestBody BrewMethodDto.CreateRequest request
    ) {
        BrewMethodDto.Response response =
                brewMethodService.createBrewMethod(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.success(response));
    }

    // 관리자가 추출법을 수정함
    @Operation(summary = "추천 추출법 수정")
    @PutMapping("/{brewMethodId}")
    public ResponseEntity<CommonResponse<BrewMethodDto.Response>>
    updateBrewMethod(
            @PathVariable Long brewMethodId,
            @Valid @RequestBody BrewMethodDto.UpdateRequest request
    ) {
        BrewMethodDto.Response response =
                brewMethodService.updateBrewMethod(brewMethodId, request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

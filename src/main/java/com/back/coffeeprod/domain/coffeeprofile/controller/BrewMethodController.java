package com.back.coffeeprod.domain.coffeeprofile.controller;

import com.back.coffeeprod.domain.coffeeprofile.dto.BrewMethodDto;
import com.back.coffeeprod.domain.coffeeprofile.service.BrewMethodService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "BrewMethod", description = "커피 추천 추출법 조회 API")
@RestController
@RequestMapping("/api/v1/brew-methods")
@RequiredArgsConstructor
public class BrewMethodController {

    private final BrewMethodService brewMethodService;

    // 추출법 목록을 조회함
    @Operation(summary = "추천 추출법 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<BrewMethodDto.Response>>>
    getBrewMethods() {
        return ResponseEntity.ok(CommonResponse.success(
                brewMethodService.getBrewMethods()
        ));
    }
}

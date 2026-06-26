package com.back.coffeeprod.domain.coffeeprofile.controller;

import com.back.coffeeprod.domain.coffeeprofile.dto.ProcessingMethodDto;
import com.back.coffeeprod.domain.coffeeprofile.service.ProcessingMethodService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "ProcessingMethod", description = "커피 가공 방식 조회 API")
@RestController
@RequestMapping("/api/v1/processing-methods")
@RequiredArgsConstructor
public class ProcessingMethodController {

    private final ProcessingMethodService processingMethodService;

    // 가공 방식 목록을 조회함
    @Operation(summary = "가공 방식 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<ProcessingMethodDto.Response>>> getProcessingMethods() {
        List<ProcessingMethodDto.Response> response = processingMethodService.getProcessingMethods();
        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

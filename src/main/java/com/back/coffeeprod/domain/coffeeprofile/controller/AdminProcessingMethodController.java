package com.back.coffeeprod.domain.coffeeprofile.controller;

import com.back.coffeeprod.domain.coffeeprofile.dto.ProcessingMethodDto;
import com.back.coffeeprod.domain.coffeeprofile.service.ProcessingMethodService;
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

@Tag(name = "Admin-ProcessingMethod", description = "관리자 커피 가공 방식 관리 API")
@SecurityRequirement(name = "jwtAuth")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/processing-methods")
@RequiredArgsConstructor
public class AdminProcessingMethodController {

    private final ProcessingMethodService processingMethodService;

    // 관리자가 가공 방식 목록을 조회함
    @Operation(summary = "관리자 가공 방식 목록 조회")
    @GetMapping
    public ResponseEntity<CommonResponse<List<ProcessingMethodDto.Response>>> getProcessingMethods() {
        List<ProcessingMethodDto.Response> response = processingMethodService.getProcessingMethods();
        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 가공 방식을 등록함
    @Operation(summary = "가공 방식 등록")
    @PostMapping
    public ResponseEntity<CommonResponse<ProcessingMethodDto.Response>> createProcessingMethod(
            @Valid @RequestBody ProcessingMethodDto.CreateRequest request
    ) {
        ProcessingMethodDto.Response response = processingMethodService.createProcessingMethod(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(response));
    }

    // 가공방식을 수정함
    @Operation(summary = "가공 방식 수정")
    @PutMapping("/{processingMethodId}")
    public ResponseEntity<CommonResponse<ProcessingMethodDto.Response>> updateProcessingMethod(
            @PathVariable Long processingMethodId,
            @Valid @RequestBody ProcessingMethodDto.UpdateRequest request
    ) {
        ProcessingMethodDto.Response response =
                processingMethodService.updateProcessingMethod(processingMethodId, request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

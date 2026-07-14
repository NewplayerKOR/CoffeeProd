package com.back.coffeeprod.domain.recommendation.controller;

import com.back.coffeeprod.domain.recommendation.dto.MemberCoffeePreferenceDto;
import com.back.coffeeprod.domain.recommendation.service.MemberCoffeePreferenceService;
import com.back.coffeeprod.global.common.CommonResponse;
import com.back.coffeeprod.global.security.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "MemberCoffeePreference", description = "회원 커피 취향 관리 API")
@SecurityRequirement(name = "jwtAuth")
@RestController
@RequestMapping("/api/v1/members/me/coffee-preference")
@RequiredArgsConstructor
public class MemberCoffeePreferenceController {

    private final MemberCoffeePreferenceService memberCoffeePreferenceService;

    // 내 커피 취향을 조회함
    @Operation(summary = "내 커피 취향 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "저장된 취향 없음")
    })
    @GetMapping
    public ResponseEntity<CommonResponse<MemberCoffeePreferenceDto.Response>>
    getMyPreference(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long memberId = userDetails.getMember().getId();

        return ResponseEntity.ok(CommonResponse.success(
                memberCoffeePreferenceService.getMyPreference(memberId)
        ));
    }

    // 내 커피 취향을 생성 또는 전체 수정함
    @Operation(
            summary = "내 커피 취향 저장",
            description = "하나 이상의 취향을 입력합니다. PUT 요청이므로 누락한 값은 null로 초기화됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "가공 방식 없음")
    })
    @PutMapping
    public ResponseEntity<CommonResponse<MemberCoffeePreferenceDto.Response>>
    upsertMyPreference(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody MemberCoffeePreferenceDto.Request request
    ) {
        Long memberId = userDetails.getMember().getId();

        return ResponseEntity.ok(CommonResponse.success(
                memberCoffeePreferenceService.upsertMyPreference(
                        memberId,
                        request
                )
        ));
    }
}

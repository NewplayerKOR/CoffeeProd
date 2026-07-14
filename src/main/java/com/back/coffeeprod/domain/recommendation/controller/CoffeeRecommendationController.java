package com.back.coffeeprod.domain.recommendation.controller;

import com.back.coffeeprod.domain.recommendation.dto.CoffeeRecommendationDto;
import com.back.coffeeprod.domain.recommendation.service.CoffeeRecommendationService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "CoffeeRecommendation", description = "규칙 기반 커피 추천 API")
@RestController
@RequestMapping("/api/v1/coffee-recommendations")
@RequiredArgsConstructor
public class CoffeeRecommendationController {

    private final CoffeeRecommendationService coffeeRecommendationService;

    // 저장된 회원 취향으로 커피를 추천함
    @Operation(
            summary = "내 취향 기반 커피 추천",
            description = "저장된 회원 커피 취향으로 판매 가능한 상품을 추천합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "추천 성공"),
            @ApiResponse(responseCode = "400", description = "추천 개수 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "저장된 취향 없음")
    })
    @SecurityRequirement(name = "jwtAuth")
    @GetMapping("/me")
    public ResponseEntity<CommonResponse<List<CoffeeRecommendationDto.Response>>>
    recommendForMe(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) Integer limit
    ) {
        Long memberId = userDetails.getMember().getId();

        return ResponseEntity.ok(CommonResponse.success(
                coffeeRecommendationService.recommendForMember(
                        memberId,
                        limit
                )
        ));
    }

    // 고객 취향으로 커피를 추천함
    @Operation(
            summary = "커피 추천",
            description = """
                    고객 취향에 맞는 판매 가능 커피를 추천합니다.
                    - ON_SALE 상태이며 재고가 있는 상품만 반환합니다.
                    - 디카페인 여부는 필수 조건으로 적용합니다.
                    - 로스팅, 원두 유형, 가공 방식, 감각 점수는 추천 점수에 반영합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "추천 성공"),
            @ApiResponse(responseCode = "400", description = "추천 조건 검증 실패"),
            @ApiResponse(responseCode = "404", description = "가공 방식을 찾을 수 없음")
    })
    @PostMapping
    public ResponseEntity<CommonResponse<List<CoffeeRecommendationDto.Response>>> recommend(
            @Valid @RequestBody CoffeeRecommendationDto.Request request
    ) {
        List<CoffeeRecommendationDto.Response> response =
                coffeeRecommendationService.recommend(request);

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

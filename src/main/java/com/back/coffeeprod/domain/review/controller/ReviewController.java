package com.back.coffeeprod.domain.review.controller;

import com.back.coffeeprod.domain.review.dto.ReviewDto;
import com.back.coffeeprod.domain.review.service.ReviewService;
import com.back.coffeeprod.global.common.CommonResponse;
import com.back.coffeeprod.global.security.auth.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    // 상품 리뷰를 공개 조회함
    @GetMapping("/products/{productId}/reviews")
    public ResponseEntity<CommonResponse<Page<ReviewDto.Response>>> getReviews(
            @PathVariable Long productId,
            @PageableDefault(
                    size = 10,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        Page<ReviewDto.Response> response = reviewService.getReviews(productId, pageable);

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 리뷰 작성을 인증 사용자로 제한함
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/products/{productId}/reviews")
    public ResponseEntity<CommonResponse<ReviewDto.Response>> createReview(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long productId,
            @Valid @RequestBody ReviewDto.Request request
    ) {
        ReviewDto.Response response = reviewService.create(
                userDetails.getMember().getId(),
                productId,
                request
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.success(response));
    }

    // 리뷰 수정을 인증 사용자로 제한함
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/reviews/{reviewId}")
    public ResponseEntity<CommonResponse<ReviewDto.Response>> updateReview(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long reviewId,
            @Valid @RequestBody ReviewDto.Request request
    ) {
        ReviewDto.Response response = reviewService.update(
                userDetails.getMember().getId(),
                reviewId,
                request
        );

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 리뷰 삭제를 인증 사용자로 제한함
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<CommonResponse<Void>> deleteReview(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long reviewId
    ) {
        reviewService.delete(
                userDetails.getMember().getId(),
                reviewId
        );

        return ResponseEntity.ok(
                CommonResponse.success(200, "리뷰가 삭제되었습니다.")
        );
    }
}

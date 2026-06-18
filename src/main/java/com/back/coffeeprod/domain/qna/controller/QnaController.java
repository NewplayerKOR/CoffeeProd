package com.back.coffeeprod.domain.qna.controller;

import com.back.coffeeprod.domain.qna.dto.QnaDto;
import com.back.coffeeprod.domain.qna.service.QnaService;
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
public class QnaController {

    private final QnaService qnaService;

    // 상품 문의 목록을 공개 조회함
    @GetMapping("/products/{productId}/qnas")
    public ResponseEntity<CommonResponse<Page<QnaDto.Response>>> getProductQnas(
            @PathVariable Long productId,
            @PageableDefault(
                    size = 10,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        Page<QnaDto.Response> response = qnaService.getProductQnas(productId, pageable);

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 로그인 회원이 상품 문의를 작성함
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/products/{productId}/qnas")
    public ResponseEntity<CommonResponse<QnaDto.Response>> createQna(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long productId,
            @Valid @RequestBody QnaDto.QuestionRequest request
    ) {
        QnaDto.Response response = qnaService.create(
                userDetails.getMember().getId(),
                productId,
                request
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.success(response));
    }

    // 작성자가 답변 전 문의를 수정함
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/qnas/{qnaId}")
    public ResponseEntity<CommonResponse<QnaDto.Response>> updateQna(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long qnaId,
            @Valid @RequestBody QnaDto.QuestionRequest request
    ) {
        QnaDto.Response response = qnaService.update(
                userDetails.getMember().getId(),
                qnaId,
                request
        );

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 작성자가 답변 전 문의를 삭제함
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/qnas/{qnaId}")
    public ResponseEntity<CommonResponse<Void>> deleteQna(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long qnaId
    ) {
        qnaService.delete(
                userDetails.getMember().getId(),
                qnaId
        );

        return ResponseEntity.ok(
                CommonResponse.success(200, "QnA가 삭제되었습니다.")
        );
    }
}

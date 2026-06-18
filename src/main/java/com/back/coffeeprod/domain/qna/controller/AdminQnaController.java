package com.back.coffeeprod.domain.qna.controller;

import com.back.coffeeprod.domain.qna.dto.QnaDto;
import com.back.coffeeprod.domain.qna.entity.QnaStatus;
import com.back.coffeeprod.domain.qna.service.QnaService;
import com.back.coffeeprod.global.common.CommonResponse;
import com.back.coffeeprod.global.security.auth.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/qnas")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminQnaController {

    private final QnaService qnaService;

    // 관리자가 문의 목록을 상태별로 조회함
    @GetMapping
    public ResponseEntity<CommonResponse<Page<QnaDto.Response>>> getQnas(
            @RequestParam(required = false) QnaStatus status,
            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        Page<QnaDto.Response> response = qnaService.getAdminQnas(status, pageable);

        return ResponseEntity.ok(CommonResponse.success(response));
    }

    // 관리자가 문의에 답변함
    @PatchMapping("/{qnaId}/answer")
    public ResponseEntity<CommonResponse<QnaDto.Response>> answerQna(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long qnaId,
            @Valid @RequestBody QnaDto.AnswerRequest request
    ) {
        QnaDto.Response response = qnaService.answer(
                userDetails.getMember().getId(),
                qnaId,
                request
        );

        return ResponseEntity.ok(CommonResponse.success(response));
    }
}

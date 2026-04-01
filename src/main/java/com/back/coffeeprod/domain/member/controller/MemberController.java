package com.back.coffeeprod.domain.member.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.global.common.ApiResponse;
import com.back.coffeeprod.global.security.auth.CustomUserDetails;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    // 내 정보 조회
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MemberDto.Response>> getMyInfo(
            // CustomUserDetails 가져오기
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        // Security 적용 시 인증된 사용자 ID 가져오기
        Long currentMemberId = userDetails.getMember().getId();

        MemberDto.Response response = memberService.getMyInfo(currentMemberId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

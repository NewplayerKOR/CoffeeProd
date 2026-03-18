package com.back.coffeeprod.domain.member.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.global.common.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api1/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    // 내 정보 조회
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MemberDto.Response>> getMyInfo() {

        // TODO: Security 적용 시 인증된 사용자 ID 가져오기
        Long currentMemberId = 1L; // 임시로 고정된 ID 사용

        MemberDto.Response response = memberService.getMyInfo(currentMemberId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

package com.back.coffeeprod.domain.member.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.global.common.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api1/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;

    // 회원가입
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<MemberDto.Response>> signUp(
            @RequestBody MemberDto.SignupRequest request) {

        MemberDto.Response response = memberService.join(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

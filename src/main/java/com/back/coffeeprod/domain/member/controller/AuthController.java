package com.back.coffeeprod.domain.member.controller;

import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.service.MemberService;
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
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "인증 및 회원가입 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;

    // 회원가입
    @Operation(summary = "회원가입", description = "이메일, 비밀번호, 이름, 닉네임으로 회원가입합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원가입 성공"),
            @ApiResponse(responseCode = "409", description = "이메일 또는 닉네임 중복")
    })
    @PostMapping("/signup")
    public ResponseEntity<CommonResponse<MemberDto.Response>> signUp(
            @Valid @RequestBody MemberDto.SignupRequest request) {

        return ResponseEntity.ok(CommonResponse.success(memberService.join(request)));
    }

    // 로그인
    @Operation(summary = "로그인", description = "이메일, 비밀번호로 로그인합니다. AccessToken과 RefreshToken을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공"),
            @ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호 불일치")
    })
    @PostMapping("/login")
    public ResponseEntity<CommonResponse<MemberDto.TokenResponse>> login(
            @Valid @RequestBody MemberDto.LoginRequest request) {

        return ResponseEntity.ok(CommonResponse.success(memberService.login(request)));
    }

    // RefreshToken 재발급
    @Operation(
            summary = "토큰 재발급",
            description = """
                    RefreshToken으로 새 AccessToken과 새 RefreshToken을 재발급합니다.
                    - RefreshToken Rotation 적용: 재발급 시 RefreshToken도 새것으로 교체
                    - 로그아웃 후 기존 RefreshToken으로 요청시 401 반환
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재발급 성공"),
            @ApiResponse(responseCode = "401", description = "유효하지 않은 RefreshToken")
    })
    @PostMapping("/reissue")
    public ResponseEntity<CommonResponse<MemberDto.ReissueResponse>> reissue(
            @Valid @RequestBody MemberDto.RefreshRequest request) {

        return ResponseEntity.ok(CommonResponse.success(memberService.reissue(request)));
    }

    // 로그아웃
    @Operation(
            summary = "로그아웃",
            description = """
                    로그아웃 처리합니다.
                    - Redis에서 RefreshToken 삭제
                    - 이후 해당 RefreshToken으로 재발급 요청 시 401 반환
                    - AccessToken은 클라이언트에서 직접 삭제 필요
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그아웃 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @SecurityRequirement(name = "jwtAuth") // 로그아웃은 AccessToken 필요
    @PostMapping("/logout")
    public ResponseEntity<CommonResponse<Void>> logout(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        memberService.logout(userDetails.getMember().getId());
        return ResponseEntity.ok(CommonResponse.success(200, "로그아웃 되었습니다."));
    }

    // 이메일 중복 확인
    @Operation(
            summary = "이메일 중복 확인",
            description = """
                    회원가입 전 이메일 중복 여부를 확인 합니다.
                    - available: true -> 사용 가능한 이메일
                    - available: false -> 이미 사용중인 이메일
                    - 탈퇴 회원의 이메일은 사용 가능으로 처리
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
    })
    @GetMapping("/check-email")
    public ResponseEntity<CommonResponse<MemberDto.EmailCheckResponse>> checkEmail(
            @RequestParam String email) {

        boolean available = memberService.checkEmail(email);
        return ResponseEntity.ok(CommonResponse.success(new MemberDto.EmailCheckResponse(available)));
    }
}

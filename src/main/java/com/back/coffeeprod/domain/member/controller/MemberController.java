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
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Member", description = "회원 정보 관리 API")
@SecurityRequirement(name = "jwtAuth")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    // 마이페이지 조회
    @Operation(summary = "마이페이지 조회", description = "로그인한 회원의 정보를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @GetMapping("/me")
    public ResponseEntity<CommonResponse<MemberDto.Response>> getMyInfo(
            // CustomUserDetails 가져오기
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        // Security 적용 시 인증된 사용자 ID 가져오기
        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(memberService.getMyInfo(memberId)));
    }

    // 내 정보 수정 (닉네임)
    @Operation(summary = "내 정보 수정", description = "닉네임을 수정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "409", description = "닉네임 중복")
    })
    @PatchMapping("/me")
    public ResponseEntity<CommonResponse<MemberDto.Response>> updateMyInfo(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody MemberDto.UpdateRequest request) {

        Long memberId = userDetails.getMember().getId();
        return ResponseEntity.ok(CommonResponse.success(memberService.updateMyInfo(memberId, request)));
    }

    // 비밀번호 변경
    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호 확인 후 새 비밀번호로 변경합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경 성공"),
            @ApiResponse(responseCode = "401", description = "현재 비밀번호 불일치")
    })
    @PatchMapping("/me/password")
    public ResponseEntity<CommonResponse<Void>> changePassword(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody MemberDto.PasswordChangeRequest request) {

        Long memberId = userDetails.getMember().getId();
        memberService.changePassword(memberId, request);
        return ResponseEntity.ok(CommonResponse.success(200, "비밀번호가 변경되었습니다."));
    }

    // 회원 탈퇴 (Soft Delete)
    @Operation(summary = "회원 탈퇴", description = "현재 비밀번호 확인 후 탈퇴 처리합니다. (Soft Delete)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "탈퇴 성공"),
            @ApiResponse(responseCode = "401", description = "비밀번호 불일치")
    })
    @DeleteMapping("/me")
    public ResponseEntity<CommonResponse<Void>> withdraw(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody MemberDto.PasswordChangeRequest request) {

        Long memberId = userDetails.getMember().getId();
        memberService.withdraw(memberId, request.getCurrentPassword());
        return ResponseEntity.ok(CommonResponse.success(200, "회원 탈퇴가 완료되었습니다."));
    }
}

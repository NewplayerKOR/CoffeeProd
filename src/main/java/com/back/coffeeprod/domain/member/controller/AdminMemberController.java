package com.back.coffeeprod.domain.member.controller;

import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

//TODO: 관리자 MemberController 구현
@Tag(name = "Admin-Member", description = "관리자 회원 관리 API")
@SecurityRequirement(name = "jwtAuth")
@PreAuthorize("hasRole('ADMIN')") // ✅ 클래스 레벨 ADMIN 권한 체크
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final MemberService memberService;

    // 전체 회원 목록 조회
    @Operation(
            summary = "전체 회원 목록 조회",
            description = """
                전체 회원 목록을 페이지네이션으로 조회합니다.
                - includeWithdrawn: true 시 탈퇴 회원 포함 조회
                - 기본값: 탈퇴 회원 제외
                """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음")
    })
    @GetMapping
    public ResponseEntity<CommonResponse<Page<MemberDto.AdminResponse>>> getAllMembers(

            @Parameter(description = "탈퇴 회원 포함 여부 (기본값: false)")
            @RequestParam(defaultValue = "false") boolean includeWithdrawn,

            @PageableDefault(size = 20, sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {

        return ResponseEntity.ok(CommonResponse.success(
                memberService.getAllMembers(includeWithdrawn, pageable)));

    }

    // 회원 상태 변경 (정지 / 활성화)
    //TODO: 회원 상태 변경 작성
}

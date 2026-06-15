package com.back.coffeeprod.domain.member.dto;

import com.back.coffeeprod.domain.member.entity.Grade;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import com.back.coffeeprod.domain.member.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class MemberDto {

    // 회원가입 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class SignupRequest {

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        private String email;

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하로 입력해야 합니다.")
        private String password;

        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 50, message = "이름은 50자 이하로 입력해야 합니다.")
        private String name;

        @NotBlank(message = "닉네임은 필수 입니다")
        @Size(max = 50, message = "닉네임은 50자 이하로 입력해야 합니다.")
        private String nickname;
    }

    // 로그인 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class LoginRequest {

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        private String email;

        @NotBlank(message = "비밀번호는 필수입니다.")
        private String password;
    }

    // 회원 정보 수정 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class UpdateRequest {

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 50, message = "닉네임은 50자 이하로 입력해야 합니다.")
        private String nickname;
    }

    // 비밀번호 변경 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class PasswordChangeRequest {

        @NotBlank(message = "현재 비밀번호는 필수입니다.")
        private String currentPassword;

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "새 비밀번호는 8자 이상 100자 이하로 입력해야 합니다.")
        private String newPassword;
    }

    // 회원 정보 응답 DTO
    @Getter
    public static class Response {
        private final Long id;
        private final String email;
        private final String name;
        private final String nickname;
        private final Grade grade;
        private final int mileage;
        private final MemberStatus status;

        public Response(Member member) {
            this.id = member.getId();
            this.email = member.getEmail();
            this.name = member.getName();
            this.nickname = member.getNickname();
            this.grade = member.getGrade();
            this.mileage = member.getMileage();
            this.status = member.getStatus();
        }
    }

    // 이메일 중복 확인 응답 DTO
    @Getter
    public static class EmailCheckResponse {
        private final boolean available;

        public EmailCheckResponse(boolean available) {
            this.available = available;
        }
    }

    // 로그인 시 토큰 반환 객체
    @Getter
    public static class TokenResponse {
        private final String accessToken;
        private final String refreshToken;

        public TokenResponse(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    // RefreshToken 재발급 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class RefreshRequest {

        @NotBlank(message = "RefreshToken은 필수입니다.")
        private String refreshToken;    // 클라이언트가 보관 중인 RefreshToken
    }

    // AccessToken 단독 응답 DTO
    // 재발급 시 AccessToken + 새 RefreshToken 함께 반환
    @Getter
    public static class ReissueResponse {
        private final String accessToken;
        private final String refreshToken;

        public ReissueResponse(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }


    // -------------------- 관리자 영역 --------------------

    // 관리자 - 회원 등급 변경 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class GradeUpdateRequest {

        @NotNull(message = "회원 등급은 필수입니다.")
        private Grade grade;
    }

    // 관리자 - 회원 상태 변경 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class StatusUpdateRequest {

        @NotNull(message = "회원 상태는 필수입니다.")
        private MemberStatus status;
    }

    // 전체 회원 목록 응답 DTO (요약)
    @Getter
    public static class AdminResponse {
        private final Long id;
        private final String email;
        private final String name;
        private final String nickname;
        private final Role role;
        private final Grade grade;
        private final int mileage;
        private final MemberStatus status;

        public AdminResponse(Member member) {
            this.id = member.getId();
            this.email = member.getEmail();
            this.name = member.getName();
            this.nickname = member.getNickname();
            this.role = member.getRole();
            this.grade = member.getGrade();
            this.mileage = member.getMileage();
            this.status = member.getStatus();
        }
    }
}

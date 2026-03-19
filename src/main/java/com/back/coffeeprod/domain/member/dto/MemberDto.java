package com.back.coffeeprod.domain.member.dto;

import com.back.coffeeprod.domain.member.entity.Grade;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;

public class MemberDto {

    // 회원가입 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class SignupRequest {
        private String email;
        private String password;
        private String name;
        private String nickname;
    }

    // 로그인 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class LoginRequest {
        private String email;
        private String password;
    }

    // 회원 정보 수정 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class UpdateRequest {
        private String nickname;
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
}

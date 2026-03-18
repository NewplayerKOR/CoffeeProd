package com.back.coffeeprod.domain.member.dto;

import com.back.coffeeprod.domain.member.entity.Grade;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;

public class MemberDto {

    @Getter
    @NoArgsConstructor
    public static class SignupRequest {
        private String email;
        private String password;
        private String name;
        private String nickname;
    }

    @Getter
    @NoArgsConstructor
    public static class UpdateRequest {
        private String nickname;
    }

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
}

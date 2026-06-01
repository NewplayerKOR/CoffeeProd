package com.back.coffeeprod.domain.member.entity;

public enum MemberStatus {
    ACTIVE, // 정상회원
    SUSPENDED, // 관리자에 의해 정지된 회원
    WITHDRAWN // 탈퇴 회원
}

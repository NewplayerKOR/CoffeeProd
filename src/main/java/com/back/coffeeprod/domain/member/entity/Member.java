package com.back.coffeeprod.domain.member.entity;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "member")
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Column(unique = true, nullable = false, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(unique = true, nullable = false, length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Grade grade;

    private int mileage = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Address> addresses = new ArrayList<>();

    @Builder
    public Member(String email, String password, String name, String nickname, Role role) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.nickname = nickname;
        this.role = role != null ? role : Role.USER; // role이 null이면 USER로 설정
        this.grade = Grade.BRONZE; // 기본 등급은 BRONZE
        this.status = MemberStatus.ACTIVE; // 기본 상태는 ACTIVE
        this.mileage = 0; // 기본 마일리지는 0
    }

    // 내 정보 수정 (닉네임)
    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void updateStatus(MemberStatus status) {
        this.status = status;
    }

    // 비밀번호 변경
    public void updatePassword(String newPassword) {
        this.password = newPassword;
    }

    /**
     * 회원 탈퇴 (Soft Delete + 개인정보 익명화)
     * <p>
     * 1. status -> WITHDRAWN 변경
     * 2. 개인식별 정보 (email, name, nickname) 익명화
     * <p>
     * - UNIQUE 제약으로 그대로 두면 email/nickname 재활용 불가
     * - 개인정보보호법상 탈퇴 회원 개인정보 파기 의무
     * - 주문/결제 이력은 FK로 참조되므로 레코드 자체는 보존
     */
    public void withdraw() {
        this.status = MemberStatus.WITHDRAWN;

        this.email = "withdrawn_" + this.id + "_" + System.currentTimeMillis() + "@deleted.com";
        this.name = "알 수 없음";
        this.nickname = "탈퇴한 회원_" + this.id;
    }

    // 마일리지 적립 / 복구
    public void addMileage(int amount) {
        this.mileage += amount;
    }

    // 마일리지 차감
    public void useMileage(int amount) {
        if (this.mileage < amount) {
            throw new IllegalArgumentException("마일리지가 부족합니다.");
        }

        this.mileage -= amount;
    }
}

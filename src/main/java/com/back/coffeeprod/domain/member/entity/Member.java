package com.back.coffeeprod.domain.member.entity;

import java.util.ArrayList;
import java.util.List;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.global.common.entity.BaseTimeEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor // Builder를 위해 사용
@Builder // Builder 패턴을 위한 어노테이션
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
    private Role role;

    @Enumerated(EnumType.STRING)
    private Grade grade;

    private int mileage = 0;

    @Enumerated(EnumType.STRING)
    private MemberStatus status;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Address> addresses = new ArrayList<>();

    public Member(String email, String password, String name, String nickname, Role role) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.nickname = nickname;
        this.role = role;
        this.grade = Grade.BRONZE; // 기본 등급은 BRONZE
        this.status = MemberStatus.ACTIVE; // 기본 상태는 ACTIVE
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void updateStatus(MemberStatus status) {
        this.status = status;
    }

    public void updatePassword(String newPassword) {
        this.password = newPassword;
    }
}

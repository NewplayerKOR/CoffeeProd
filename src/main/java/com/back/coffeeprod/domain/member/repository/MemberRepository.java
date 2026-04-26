package com.back.coffeeprod.domain.member.repository;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmail(String email);

    // 전체 회원 대상
    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    // 특정 상태의 회원만 대상으로 중복 검증
    boolean existsByEmailAndStatus(String email, MemberStatus status);

    boolean existsByNicknameAndStatus(String nickname, MemberStatus status);
}

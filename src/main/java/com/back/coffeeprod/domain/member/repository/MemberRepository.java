package com.back.coffeeprod.domain.member.repository;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmail(String email);

    // 전체 회원 대상
    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    // 특정 상태의 회원만 대상으로 중복 검증
    boolean existsByEmailAndStatus(String email, MemberStatus status);

    boolean existsByNicknameAndStatus(String nickname, MemberStatus status);

    // 관리자 - 전체 회원 목록 조회 (탈퇴 회원 포함 여부 선택)
    @Query("""
            SELECT m FROM Member M
            WHERE (:includedWithdrawn = true OR m.status != 'WITHDRAWN')
            ORDER BY m.createdAt DESC
            """)
    Page<Member> findAllForAdmin(
            @Param("includedWithdrawn") boolean includedWithdrawn,
            Pageable pageable);
}

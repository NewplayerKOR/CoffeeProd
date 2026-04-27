package com.back.coffeeprod.domain.member.service;

import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 읽기 전용
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    // 회원가입
    @Transactional
    public MemberDto.Response join(MemberDto.SignupRequest request) {
        // 이메일 중복 검증
        if (memberRepository.existsByEmailAndStatus(request.getEmail(), MemberStatus.ACTIVE)) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        // 닉네임 중복 검증
        if (memberRepository.existsByNicknameAndStatus(request.getNickname(), MemberStatus.ACTIVE)) {
            throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
        }

        String encodedPassword = passwordEncoder.encode(request.getPassword());

        Member member = Member.builder()
                .email(request.getEmail())
                .password(encodedPassword)
                .name(request.getName())
                .nickname(request.getNickname())
                .role(Role.USER) // 기본 역할은 USER
                .build();

        return new MemberDto.Response(memberRepository.save(member));
    }

    // 로그인
    public MemberDto.TokenResponse login(MemberDto.LoginRequest request) {
        // 이메일로 회원 조회 (실패 시 통합 에러)
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));

        // 비밀번호 검증 (실패 시 통합 에러)
        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 회원 상태 검증 - 탈퇴 회원 로그인 불가
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new CustomException(ErrorCode.WITHDRAW_MEMBER);
        }

        // 비밀번호 일치시 토큰 발급
        String accessToken = jwtUtil.generateAccessToken(member.getId(), member.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(member.getId());

        // 발급된 토큰 반환
        return new MemberDto.TokenResponse(accessToken, refreshToken);
    }

    // 내 정보 조회
    public MemberDto.Response getMyInfo(Long memberId) {
        Member member = findMemberById(memberId);
        return new MemberDto.Response(member);
    }

    // 내 정보 수정 (닉네임)
    @Transactional
    public MemberDto.Response updateMyInfo(Long memberId, MemberDto.UpdateRequest request) {
        Member member = findMemberById(memberId);

        // 새로운 닉네임을 다른 회원이 사용중인지 검증
        if (memberRepository.existsByNickname(request.getNickname())) {
            throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
        }

        member.updateNickname(request.getNickname());
        return new MemberDto.Response(member);
    }

    @Transactional
    public void changePassword(Long memberId, MemberDto.PasswordChangeRequest request) {
        Member member = findMemberById(memberId);

        // 현재 비밀번호 일치 여부 검증
        if (!passwordEncoder.matches(request.getCurrentPassword(), member.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        // 새로운 비밀번호 암호화 후 저장
        String encodedNewPassword = passwordEncoder.encode(request.getNewPassword());
        member.updatePassword(encodedNewPassword);
    }

    // 회원 탈퇴 Soft Delete
    @Transactional
    public void withdraw(Long memberId, String currentPassword) {
        Member member = findMemberById(memberId);

        // 탈퇴 전 현재 비밀번호로 본인 확인
        if (!passwordEncoder.matches(currentPassword, member.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        member.withdraw();
    }

    // [내부 공용] ID로 회원 조회
    public Member findMemberById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }
}

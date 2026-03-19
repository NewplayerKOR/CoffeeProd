package com.back.coffeeprod.domain.member.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import com.back.coffeeprod.global.security.jwt.JwtUtil;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 읽기 전용
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    // 일반 회원가입
    @Transactional
    public MemberDto.Response join(MemberDto.SignupRequest request) {
        // 이메일 중복 검증
        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        // 닉네임 중복 검증
        if (memberRepository.existsByNickname(request.getNickname())) {
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

    private Member findMemberById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }

}

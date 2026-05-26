package com.back.coffeeprod.domain.member.service;

import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import com.back.coffeeprod.global.security.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final RefreshTokenService refreshTokenService;

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

        // 발급한 RefreshToken을 Redis에 저장
        // Key: "refresh: {memberId}", TTL: jwt.refresh-expiration
        refreshTokenService.save(member.getId(), refreshToken);

        // 발급된 토큰 반환
        return new MemberDto.TokenResponse(accessToken, refreshToken);
    }

    // RefreshToken 재발급
    public MemberDto.ReissueResponse reissue(MemberDto.RefreshRequest request) {
        String requestToken = request.getRefreshToken();

        // 1. RefreshToken 자체 유효성 검사 (서명, 만료 여부)
        if (!jwtUtil.validateToken(requestToken)) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        // 2. Token에서 memberId 추출
        Long memberId = jwtUtil.getMemberIdFromToken(requestToken);

        // 3. Redis에 저장된 토큰과 일치 여부 검증
        if (!refreshTokenService.isValid(memberId, requestToken)) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        // 4. 회원 조회 (탈퇴 여부 확인)
        Member member = findMemberById(memberId);
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new CustomException(ErrorCode.WITHDRAW_MEMBER);
        }

        // 5. 새 토큰 발급
        String newAccessToken = jwtUtil.generateAccessToken(member.getId(), member.getRole());
        String newRefreshToken = jwtUtil.generateRefreshToken(member.getId());

        // 6. Redis의 RefreshToken 갱신
        refreshTokenService.save(member.getId(), newRefreshToken);
        //TODO 검증필요1

        return new MemberDto.ReissueResponse(newAccessToken, newRefreshToken);
    }

    // 로그아웃
    public void logout(Long memberId) {
        // Redis에서 RefreshToken 삭제
        // -> 이후 해당 RefreshToken으로 재발급 요청 시 isValid() = flase -> 거부
        refreshTokenService.delete(memberId);
    }

    // 이메일 중복 확인
    public boolean checkEmail(String email) {
        // ACTIVE 회원 중 동일 이메일 존재 여부 반환
        // true -> 사용가능 (중복 없음)
        // false -> 사용불가 (중복 있음)
        return !memberRepository.existsByEmailAndStatus(email, MemberStatus.ACTIVE);
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

        // 탈퇴 시 Redis의 RefreshToken도 삭제
        refreshTokenService.delete(memberId);
        member.withdraw();
    }

    // [내부 공용] ID로 회원 조회
    public Member findMemberById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }

    // ----------- 관리자  영역 -----------
    // [관리자] 전체 회원 목록 조회
    public Page<MemberDto.AdminResponse> getAllMembers(
            boolean includedWithdrawn, Pageable pageable) {

        return memberRepository.findAllForAdmin(includedWithdrawn, pageable)
                .map(MemberDto.AdminResponse::new);
    }

    // [관리자] 회원 등급 변경
    @Transactional
    public MemberDto.AdminResponse updateMemberGrade(
            Long memberId, MemberDto.GradeUpdateRequest request) {

        Member member = findMemberById(memberId);
        member.updateGrade(request.getGrade());
        return new MemberDto.AdminResponse(member);
    }

    // [관리자] 회원 상태 변경 (정지/활성화)
    @Transactional
    public MemberDto.AdminResponse updateMemberStatus(
            Long memberId, MemberDto.StatusUpdateRequest request) {

        Member member = findMemberById(memberId);
        member.updateStatus(request.getStatus());
        return new MemberDto.AdminResponse(member);
    }
}

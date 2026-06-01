package com.back.coffeeprod.global.security.auth;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    @Override
    public UserDetails loadUserByUsername(String memberIdStr) throws UsernameNotFoundException {
        Long memberId = Long.parseLong(memberIdStr);

        // DB에서 회원 조회
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다. ID: " + memberId));

        // 탈퇴, 정지 회원의 기존 토큰으로 API 접근 차단
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new UsernameNotFoundException("탈퇴한 회원입니다. ID: " + memberId);
        }

        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new UsernameNotFoundException("정지된 회원입니다. ID: " + memberId);
        }

        // 시큐리티 규격에 맞게 변환
        return new CustomUserDetails(member);
    }
}

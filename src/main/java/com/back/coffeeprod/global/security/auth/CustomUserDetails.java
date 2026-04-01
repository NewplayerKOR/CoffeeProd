package com.back.coffeeprod.global.security.auth;

import java.util.Collection;
import java.util.Collections;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.back.coffeeprod.domain.member.entity.Member;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {

    private final Member member;

    // 회원 권한을 시큐리티에 맞는 형태로 변환
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()));
    }

    // 비밀번호 반환 (JWT를 쓰므로 이 단계에서는 쓰지 않으나 규격상 필요)
    @Override
    public String getPassword() {
        return member.getPassword();
    }

    // pk 문자열 반환
    @Override
    public String getUsername() {
        return member.getId().toString();
    }

    // 계정 만로, 잠김 여부 등
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}

package com.back.coffeeprod.global.security.jwt;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.back.coffeeprod.global.security.auth.CustomUserDetailsService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1. HTTP 헤더에서 'Bearer'로 시작하는 토큰 추출
        String token = resolveToken(request);

        // 2. 토큰 존재, 유효한지 검증
        if (token != null && jwtUtil.validateToken(token)) {

            // 3. 토큰이 진짜면 ID 추출
            Long memberId = jwtUtil.getMemberIdFromToken(token);

            // 4. DB에서 해당 회원 정보로 UserDetails로 변환
            UserDetails userdetails = customUserDetailsService.loadUserByUsername(memberId.toString());

            // 5. '인증 완료'인 Authentication 객체 생성
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(userdetails,
                    null, userdetails.getAuthorities());

            // 6. SecurityContext에 저장(로그인 한 유저)
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        // 7. 다음 필터, 컨트롤러로 이동
        filterChain.doFilter(request, response);
    }

    // 헤더에서 토큰만 잘라냄
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

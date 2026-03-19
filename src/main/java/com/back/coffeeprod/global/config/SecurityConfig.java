package com.back.coffeeprod.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Spring Security에서 사용할 PasswordEncoder 빈을 정의
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 보안 필터 체인 설정
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // REST API이므로 CSRF 보안을 비활성화
                .csrf(csrf -> csrf.disable())

                // 폼 로그인, 기본 HTTP 로그인 비활성화(JWT 사용)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                // 세션 미사용
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 요청에 대한 권한 설정
                .authorizeHttpRequests(auth -> auth
                        // 인증 없이 접근 허용할 엔드포인트 설정 (예: 회원가입, 로그인)
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // 에러 페이지 접근 허용
                        .requestMatchers("/error").permitAll()
                        // 관리자 전용 경로는 ADMIN 권한 필요
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 그 외 모든 요청은 인증(로그인) 필요
                        .anyRequest().authenticated());

        // TODO: JWT 인증 필터 추가

        return http.build();
    }
}

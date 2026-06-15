package com.back.coffeeprod.global.config;

import com.back.coffeeprod.global.security.handler.RestAccessDeniedHandler;
import com.back.coffeeprod.global.security.handler.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.back.coffeeprod.global.security.auth.CustomUserDetailsService;
import com.back.coffeeprod.global.security.jwt.JwtAuthenticationFilter;
import com.back.coffeeprod.global.security.jwt.JwtUtil;

import lombok.RequiredArgsConstructor;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    // Spring Security에서 사용할 PasswordEncoder 빈을 정의
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 보안 필터 체인 설정
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 프론트엔드와 백엔드의 포트가 다를 때 브라우저 CORS 차단을 방지
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // REST API이므로 CSRF 보안을 비활성화
                .csrf(csrf -> csrf.disable())

                // 폼 로그인, 기본 HTTP 로그인 비활성화(JWT 사용)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                // 세션 미사용
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 인증 실패/권한 실패 응답을 CommonResponse 형식으로 통일
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler)
                )

                // 요청에 대한 권한 설정
                .authorizeHttpRequests(auth -> auth
                        // 인증 없이 접근 허용할 엔드포인트 설정 (예: 회원가입, 로그인)
                        .requestMatchers(
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login",
                                "/api/v1/auth/reissue",
                                "/api/v1/auth/check-email",
                                "/api/v1/products/**",
                                "/api/v1/categories/**"
                        ).permitAll()
                        // 에러 페이지 접근 허용
                        .requestMatchers("/error").permitAll()
                        // Swagger UI 및 API Docs 접근 허용
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // 관리자 전용 경로는 ADMIN 권한 필요
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 그 외 모든 요청은 인증(로그인) 필요
                        .anyRequest().authenticated())

                // 기본 로그인 필터대신 작성한 JWT 인증 필터 사용
                .addFilterBefore(new JwtAuthenticationFilter(jwtUtil, customUserDetailsService),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // CORS 정책 설정
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 로컬 프론트엔드 기본 개발 포트 허용
        // 배포 시 실제 도메인만 허용하도록 별도 설정으로 분리하는 것을 권장
        configuration.setAllowedOrigins(List.of(
                "http://localhost:3000",
                "http://127.0.0.1:3000",
                "http://localhost:5173",
                "http://127.0.0.1:5173"
        ));

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

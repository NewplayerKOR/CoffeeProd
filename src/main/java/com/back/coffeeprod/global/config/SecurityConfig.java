package com.back.coffeeprod.global.config;

import com.back.coffeeprod.global.security.auth.CustomUserDetailsService;
import com.back.coffeeprod.global.security.handler.RestAccessDeniedHandler;
import com.back.coffeeprod.global.security.handler.RestAuthenticationEntryPoint;
import com.back.coffeeprod.global.security.jwt.JwtAuthenticationFilter;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

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
                        // 인증 없이 접근할 인증 API를 설정함
                        .requestMatchers(
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login",
                                "/api/v1/auth/reissue",
                                "/api/v1/auth/check-email"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/coffee-recommendations"
                        ).permitAll()
                        // 상품, 카테고리, 리뷰, QnA 조회만 공개함
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/products/**",
                                "/api/v1/categories/**",
                                "/api/v1/coffee-profiles/**",
                                "/api/v1/processing-methods/**",
                                "/api/v1/flavor-notes/**",
                                "/api/v1/brew-methods/**"
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

        // 로컬/배포 환경별 허용 Origin은 환경변수 CORS_ALLOWED_ORIGINS로 관리한다.
        configuration.setAllowedOrigins(allowedOrigins);


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

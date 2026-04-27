package com.back.coffeeprod.global.security.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${jwt.refresh-expiration}")
    private Long refreshExpiration; // 밀리초 단위

    // Redis Key 접두사 - "refresh:1", "refresh:2" 형태로 저장
    private static final String PREFIX = "refresh";

    // RefreshToken 저장

    /**
     * 로그인 / 토큰 재발급 시 호출
     * Key  : "refresh:{memberId}"
     * Value: "refreshToken 문자열
     * TTL: jwt.refresh-expiration (밀리초 -> 초 변환)
     */
    public void save(Long memberId, String refreshToken) {
        redisTemplate.opsForValue().set(
                PREFIX + memberId,  // Key
                refreshToken,            // Value
                refreshExpiration,       // TTL 값
                TimeUnit.MILLISECONDS    // TTL 단위
        );
    }

    // RefreshToken 조회
    public String get(Long memberId) {
        return redisTemplate.opsForValue().get(PREFIX + memberId);
    }

    // RefreshToken 삭제
    public void delete(Long memberId) {
        redisTemplate.delete(PREFIX + memberId);
    }

    // RefreshToken 유효성 검증
    public boolean isValid(Long memberId, String refreshToken) {
        String stored = get(memberId);

        // Redis에 토큰 없으면 false (만료 or 로그아웃)
        if (stored == null) {
            return false;
        }

        // 저장된 토큰과 요청 토큰 일치 여부 반환
        return stored.equals(refreshToken);
    }
}

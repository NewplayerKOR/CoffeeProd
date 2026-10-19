package com.back.coffeeprod.qa.auth;

import com.back.coffeeprod.domain.member.entity.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class Qa7RefreshTokenRegressionTest extends Qa7HttpSupport {
    @Test
    @DisplayName("같은 초 연속 로그인은 이전 RefreshToken을 거부하고 최신 토큰만 재발급한다")
    void repeatedLoginReplacesRefreshToken() throws Exception {
        var member = member(Role.USER);
        try {
            // Retry only a second-boundary crossing; do not sleep to avoid the collision.
            for (int attempt = 0; attempt < 8; attempt++) {
                String previous = login(member).path("refreshToken").asText();
                String current = login(member).path("refreshToken").asText();
                long previousIat = JSON.readTree(Base64.getUrlDecoder().decode(previous.split("\\.")[1]))
                        .path("iat").asLong();
                long currentIat = JSON.readTree(Base64.getUrlDecoder().decode(current.split("\\.")[1]))
                        .path("iat").asLong();
                if (previousIat != currentIat) {
                    refreshTokens.delete(member.getId());
                    continue;
                }

                sameToken(current, refreshTokens.get(member.getId()));
                int replayStatus = reissue(previous).statusCode();
                System.out.printf("DEV5_RELOGIN issuedAtSame=true tokenChanged=%s replayHttp=%d%n",
                        !previous.equals(current), replayStatus);
                assertAll(
                        () -> assertFalse(previous.equals(current), "연속 로그인 토큰 교체 (원문 생략)"),
                        () -> assertEquals(401, replayStatus, "이전 로그인 토큰 재사용 거부"));
                sameToken(current, refreshTokens.get(member.getId()));

                var result = data(reissue(current), 200);
                String next = result.path("refreshToken").asText();
                assertFalse(current.equals(next), "최신 로그인 토큰 재발급 (원문 생략)");
                sameToken(next, refreshTokens.get(member.getId()));
                error(reissue(current), 401, "유효하지 않은 토큰입니다.");
                data(request("GET", "/api/v1/members/me", null, result.path("accessToken").asText()), 200);
                return;
            }
            fail("동일 초 연속 로그인 표본을 제한된 8회 내 얻지 못함");
        } finally {
            refreshTokens.delete(member.getId());
        }
    }
}

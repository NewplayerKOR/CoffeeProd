package com.back.coffeeprod.qa.auth;

import com.back.coffeeprod.domain.member.entity.Role;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Executed unchanged with a storage double and with the dedicated Redis 7 container. */
class Qa7RedisContractTest extends Qa7HttpSupport {
    @Test @DisplayName("로그인 RefreshToken은 회원 키에 밀리초 설정 TTL로 저장된다")
    void loginStorageAndTtl() throws Exception {
        var member = member(Role.USER); String token = login(member).path("refreshToken").asText();
        sameToken(token, redis.opsForValue().get(key(member)));
        long ttl = redis.getExpire(key(member), TimeUnit.MILLISECONDS);
        assertTrue(ttl > REFRESH_TTL - 5000 && ttl <= REFRESH_TTL, "설정 TTL과 밀리초 단위 일치");
        assertTrue(refreshTokens.isValid(member.getId(), token));
    }

    @Test @DisplayName("저장 교체는 한 회원 키만 갱신하고 TTL을 복원한다")
    void replacementResetsTtlAndPreservesOtherMember() throws Exception {
        var first = member(Role.USER); var second = member(Role.USER);
        refreshTokens.save(first.getId(), "qa7-original"); refreshTokens.save(second.getId(), "qa7-other-member");
        assertTrue(redis.expire(key(first), 30, TimeUnit.SECONDS));
        refreshTokens.save(first.getId(), "qa7-replaced");
        sameToken("qa7-replaced", redis.opsForValue().get(key(first)));
        sameToken("qa7-other-member", redis.opsForValue().get(key(second)));
        assertFalse(refreshTokens.isValid(first.getId(), "qa7-original"));
        long ttl = redis.getExpire(key(first), TimeUnit.MILLISECONDS);
        assertTrue(ttl > REFRESH_TTL - 5000 && ttl <= REFRESH_TTL);
    }

    @Test @DisplayName("Redis 저장 만료는 아직 유효한 JWT의 재발급을 401로 거부한다")
    void storageExpiryRejectsValidJwt() throws Exception {
        var member = member(Role.USER); String token = login(member).path("refreshToken").asText();
        assertTrue(jwt.validateToken(token)); assertTrue(redis.expire(key(member), 150, TimeUnit.MILLISECONDS));
        if (!realRedis()) MEMORY.offset.addAndGet(200);
        await(() -> refreshTokens.get(member.getId()) == null);
        assertTrue(jwt.validateToken(token));
        error(reissue(token), 401, "유효하지 않은 토큰입니다.");
        assertEquals(-2L, redis.getExpire(key(member), TimeUnit.MILLISECONDS));
    }

    @Test @DisplayName("로그아웃은 해당 회원 키만 삭제하고 반복해도 다른 회원 토큰을 보존한다")
    void logoutDeletesOnlyCurrentMember() throws Exception {
        var first = member(Role.USER); var second = member(Role.USER);
        var firstTokens = login(first); String otherToken = login(second).path("refreshToken").asText();
        String access = firstTokens.path("accessToken").asText();
        for (int i = 0; i < 2; i++) response(request("POST", "/api/v1/auth/logout", null, access), 200);
        assertNull(redis.opsForValue().get(key(first))); sameToken(otherToken, redis.opsForValue().get(key(second)));
        error(reissue(firstTokens.path("refreshToken").asText()), 401, "유효하지 않은 토큰입니다.");
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "5초 이내 Redis 키 만료");
    }
}

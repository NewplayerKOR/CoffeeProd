package com.back.coffeeprod.qa.auth;

import com.back.coffeeprod.domain.member.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Qa7AuthLifecycleTest extends Qa7HttpSupport {
    @Test @DisplayName("가입은 비밀번호를 암호화하고 일반회원과 공개 응답을 저장한다")
    void signupPersistsEncodedPassword() throws Exception {
        String id = unique(); String email = id + "@test.invalid";
        long before = members.count();
        var result = data(request("POST", "/api/v1/auth/signup", Map.of("email", email, "password", PASSWORD,
                "name", "합성 회원", "nickname", id), null), 200);
        var stored = members.findById(result.path("id").asLong()).orElseThrow();
        assertEquals(before + 1, members.count()); assertEquals(Role.USER, stored.getRole());
        assertTrue(passwords.matches(PASSWORD, stored.getPassword())); assertFalse(PASSWORD.equals(stored.getPassword()));
        assertEquals(email, result.path("email").asText()); assertFalse(result.has("password"));
        assertEquals(MemberStatus.ACTIVE, stored.getStatus());
    }

    @ParameterizedTest(name = "중복 {0}") @ValueSource(strings = {"email", "nickname"})
    @DisplayName("가입의 이메일과 닉네임 중복은 409이며 회원 수가 유지된다")
    void signupDuplicates(String field) throws Exception {
        var member = member(Role.USER); long before = members.count();
        var body = new HashMap<String, Object>(Map.of("email", unique() + "@test.invalid", "nickname", unique(),
                "password", PASSWORD, "name", "합성 회원"));
        body.put(field, field.equals("email") ? member.getEmail() : member.getNickname());
        error(request("POST", "/api/v1/auth/signup", body, null), 409,
                field.equals("email") ? "이미 사용 중인 이메일입니다." : "이미 사용 중인 닉네임입니다.");
        assertEquals(before, members.count());
    }

    @ParameterizedTest(name = "잘못된 {0}") @ValueSource(strings = {"email", "password", "name", "nickname"})
    @DisplayName("가입의 잘못된 입력은 필드 오류 400이며 저장하지 않는다")
    void invalidSignup(String field) throws Exception {
        long before = members.count();
        var body = new HashMap<String, Object>(Map.of("email", unique() + "@test.invalid", "nickname", unique(),
                "password", PASSWORD, "name", "합성 회원"));
        body.put(field, field.equals("email") ? "invalid" : field.equals("password") ? "short" : " ");
        var root = response(request("POST", "/api/v1/auth/signup", body, null), 400);
        assertTrue(root.path("errors").isArray());
        assertTrue(root.path("errors").valueStream().anyMatch(error -> field.equals(error.path("field").asText())));
        assertEquals(before, members.count());
    }

    @ParameterizedTest(name = "{0}") @ValueSource(booleans = {false, true})
    @DisplayName("없는 이메일과 틀린 비밀번호는 같은 로그인 401이며 토큰을 저장하지 않는다")
    void invalidCredentials(boolean absent) throws Exception {
        var member = member(Role.USER);
        error(request("POST", "/api/v1/auth/login", Map.of("email", absent ? unique() + "@test.invalid" : member.getEmail(),
                "password", absent ? PASSWORD : "wrong-password"), null), 401, "이메일 또는 비밀번호가 일치하지 않습니다.");
        assertNull(refreshTokens.get(member.getId()));
    }

    @ParameterizedTest(name = "로그인과 재발급 / {0}") @EnumSource(value = MemberStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("정지와 탈퇴 회원의 로그인과 재발급은 거부하고 저장 토큰을 바꾸지 않는다")
    void inactiveLoginAndReissue(MemberStatus state) throws Exception {
        var member = member(Role.USER); var tokens = login(member);
        String refresh = tokens.path("refreshToken").asText(); status(member.getId(), state);
        int expected = state == MemberStatus.SUSPENDED ? 403 : 401;
        String message = state == MemberStatus.SUSPENDED ? "정지된 회원입니다." : "탈퇴한 회원입니다.";
        error(request("POST", "/api/v1/auth/login", Map.of("email", member.getEmail(), "password", PASSWORD), null), expected, message);
        error(reissue(refresh), expected, message); sameToken(refresh, refreshTokens.get(member.getId()));
    }

    @Test @DisplayName("로그인부터 재발급과 로그아웃까지 이전 RefreshToken을 거부한다")
    void completeLifecycle() throws Exception {
        var member = member(Role.USER); var initial = login(member);
        String oldRefresh = initial.path("refreshToken").asText();
        String access = initial.path("accessToken").asText();
        assertTrue(jwt.validateToken(access)); assertTrue(jwt.validateToken(oldRefresh));
        sameToken(oldRefresh, refreshTokens.get(member.getId()));
        assertEquals(member.getId().longValue(), data(request("GET", "/api/v1/members/me", null, access), 200).path("id").asLong());
        var next = data(reissue(oldRefresh), 200);
        String newRefresh = next.path("refreshToken").asText();
        sameToken(newRefresh, refreshTokens.get(member.getId()));
        int oldStatus = reissue(oldRefresh).statusCode();
        System.out.printf("QA7_LIFECYCLE tokenChanged=%s replayHttp=%d%n", !oldRefresh.equals(newRefresh), oldStatus);
        data(request("GET", "/api/v1/members/me", null, next.path("accessToken").asText()), 200);
        response(request("POST", "/api/v1/auth/logout", null, next.path("accessToken").asText()), 200);
        assertNull(refreshTokens.get(member.getId()));
        error(reissue(newRefresh), 401, "유효하지 않은 토큰입니다.");
        // Existing contract: access tokens are removed by the client, not server-revoked at logout.
        data(request("GET", "/api/v1/members/me", null, access), 200);
        assertAll(() -> assertFalse(oldRefresh.equals(newRefresh), "Rotation은 새 토큰이어야 한다 (원문 생략)"),
                () -> assertEquals(401, oldStatus, "이전 RefreshToken 재사용 거부"));
    }

    @Test @DisplayName("같은 초에 재발급해도 RefreshToken은 교체되고 이전 토큰은 거부된다")
    void sameSecondRotation() throws Exception {
        // No sleeps to conceal the second-resolution collision. Retry only when a pair crosses a second.
        for (int attempt = 0; attempt < 8; attempt++) {
            var member = member(Role.USER); var first = login(member);
            String previous = first.path("refreshToken").asText();
            var next = data(reissue(previous), 200);
            String current = next.path("refreshToken").asText();
            long firstIat = JSON.readTree(Base64.getUrlDecoder().decode(previous.split("\\.")[1])).path("iat").asLong();
            long nextIat = JSON.readTree(Base64.getUrlDecoder().decode(current.split("\\.")[1])).path("iat").asLong();
            if (firstIat != nextIat) { refreshTokens.delete(member.getId()); continue; }
            int replayStatus = reissue(previous).statusCode();
            System.out.printf("QA7_SAME_SECOND issuedAtSame=true tokenChanged=%s replayHttp=%d%n", !previous.equals(current), replayStatus);
            refreshTokens.delete(member.getId());
            assertAll(() -> assertFalse(previous.equals(current), "동일 초 Rotation 충돌 (토큰 원문 생략)"),
                    () -> assertEquals(401, replayStatus, "동일 초 이전 토큰 재사용은 401"));
            return;
        }
        fail("동일 초 재발급 표본을 제한된 8회 내 얻지 못함");
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "expired", "tampered", "malformed"})
    @DisplayName("누락 만료 서명변조 형식오류 AccessToken은 보호 API에서 401이다")
    void invalidAccess(String kind) throws Exception {
        var member = member(Role.USER);
        String token = switch (kind) { case "missing" -> null; case "expired" -> signed(member.getId(), true, false);
            case "tampered" -> signed(member.getId(), false, true); default -> "not-a-jwt"; };
        error(request("GET", "/api/v1/members/me", null, token), 401, "인증이 필요합니다.");
    }

    @ParameterizedTest @ValueSource(strings = {"expired", "tampered", "absent", "mismatch"})
    @DisplayName("유효하지 않거나 저장소와 다른 RefreshToken은 401이며 저장값을 보존한다")
    void invalidRefresh(String kind) throws Exception {
        var member = member(Role.USER); String stored = login(member).path("refreshToken").asText();
        String candidate = switch (kind) { case "expired" -> signed(member.getId(), true, false);
            case "tampered" -> signed(member.getId(), false, true); default -> stored; };
        if (kind.equals("absent")) refreshTokens.delete(member.getId());
        if (kind.equals("mismatch")) refreshTokens.save(member.getId(), "qa7-other-stored-value");
        if (kind.equals("expired") || kind.equals("tampered")) refreshTokens.save(member.getId(), candidate);
        String before = refreshTokens.get(member.getId());
        error(reissue(candidate), 401, "유효하지 않은 토큰입니다."); sameToken(before, refreshTokens.get(member.getId()));
    }

    @Test @DisplayName("이전 시각에 발급된 유효 RefreshToken은 교체 후 재사용이 거부된다")
    void earlierIssuedTokenRotates() throws Exception {
        var member = member(Role.USER);
        // Persist a valid synthetic token issued two minutes earlier; do not delay the collision cases.
        String previous = signed(member.getId(), false, false); refreshTokens.save(member.getId(), previous);
        var result = data(reissue(previous), 200); String current = result.path("refreshToken").asText();
        assertFalse(previous.equals(current), "이전 시각 토큰 교체 (원문 생략)");
        sameToken(current, refreshTokens.get(member.getId()));
        error(reissue(previous), 401, "유효하지 않은 토큰입니다.");
        data(request("GET", "/api/v1/members/me", null, result.path("accessToken").asText()), 200);
    }

    @Test @DisplayName("빈 재발급 입력은 400이고 비인증 로그아웃은 401이다")
    void invalidLifecycleInput() throws Exception {
        response(request("POST", "/api/v1/auth/reissue", Map.of("refreshToken", " "), null), 400);
        error(request("POST", "/api/v1/auth/logout", null, null), 401, "인증이 필요합니다.");
    }

    @ParameterizedTest(name = "기존 AccessToken / {0}") @EnumSource(value = MemberStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("발급된 AccessToken도 현재 회원이 정지 또는 탈퇴이면 거부된다")
    void inactiveExistingToken(MemberStatus state) throws Exception {
        var member = member(Role.USER); String token = access(member); status(member.getId(), state);
        error(request("GET", "/api/v1/members/me", null, token), state == MemberStatus.SUSPENDED ? 403 : 401,
                state == MemberStatus.SUSPENDED ? "정지된 회원입니다." : "탈퇴한 회원입니다.");
    }

    @Test @DisplayName("회원 삭제 후 기존 토큰과 존재하지 않는 회원 토큰은 401이다")
    void removedMember() throws Exception {
        var member = member(Role.USER); String token = access(member); members.deleteById(member.getId());
        error(request("GET", "/api/v1/members/me", null, token), 401, "인증이 필요합니다.");
        error(request("GET", "/api/v1/members/me", null, signed(Long.MAX_VALUE, false, false)), 401, "인증이 필요합니다.");
    }

    @Test @DisplayName("탈퇴 HTTP는 개인정보를 익명화하고 RefreshToken을 삭제한다")
    void withdrawDeletesRefresh() throws Exception {
        var member = member(Role.USER); var tokens = login(member);
        String access = tokens.path("accessToken").asText(); String refresh = tokens.path("refreshToken").asText();
        response(request("DELETE", "/api/v1/members/me", Map.of("currentPassword", PASSWORD), access), 200);
        var stored = members.findById(member.getId()).orElseThrow();
        assertEquals(MemberStatus.WITHDRAWN, stored.getStatus()); assertFalse(member.getEmail().equals(stored.getEmail()));
        assertFalse(member.getNickname().equals(stored.getNickname())); assertNull(refreshTokens.get(member.getId()));
        error(reissue(refresh), 401, "유효하지 않은 토큰입니다.");
        error(request("GET", "/api/v1/members/me", null, access), 401, "탈퇴한 회원입니다.");
    }

    @Test @DisplayName("기존 토큰의 관리자 권한도 현재 DB 역할 변경을 따른다")
    void roleChangesApplyImmediately() throws Exception {
        var member = member(Role.ADMIN); String token = access(member);
        data(request("GET", "/api/v1/admin/members", null, token), 200);
        jdbc.update("update member set role='USER' where member_id=?", member.getId());
        error(request("GET", "/api/v1/admin/members", null, token), 403, "접근 권한이 없습니다.");
        jdbc.update("update member set role='ADMIN' where member_id=?", member.getId());
        data(request("GET", "/api/v1/admin/members", null, token), 200);
    }
}

package com.back.coffeeprod.qa.auth;

import com.back.coffeeprod.domain.member.entity.Role;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Existing HTTP/security/Redis harness; header and body can belong to different members. */
class DevTask9JwtHttpIntegrationTest extends Qa7HttpSupport {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void refreshBodyIdentityIsIndependentOfOptionalBearer(boolean bearer) throws Exception {
        var owner = member(Role.USER); var headerOwner = member(Role.USER);
        String refresh = login(owner).path("refreshToken").asText();
        String header = bearer ? access(headerOwner) : null;
        var result = data(request("POST", "/api/v1/auth/reissue", Map.of("refreshToken", refresh), header), 200);
        assertEquals(owner.getId(), jwt.getMemberIdFromToken(result.path("accessToken").asText()));
        String next = result.path("refreshToken").asText(); assertFalse(refresh.equals(next));
        sameToken(next, refreshTokens.get(owner.getId()));
        error(reissue(refresh), 401, "유효하지 않은 토큰입니다.");
        data(request("GET", "/api/v1/members/me", null, result.path("accessToken").asText()), 200);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void authenticatedHeaderDoesNotAuthorizeAnInvalidRefreshBody(boolean bearer) throws Exception {
        var owner = member(Role.USER); String header = bearer ? access(owner) : null;
        error(request("POST", "/api/v1/auth/reissue", Map.of("refreshToken", "invalid"), header), 401, "유효하지 않은 토큰입니다.");
    }
}

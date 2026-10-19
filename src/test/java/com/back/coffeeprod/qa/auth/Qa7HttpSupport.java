package com.back.coffeeprod.qa.auth;

import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import com.back.coffeeprod.global.security.service.RefreshTokenService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP/security/services. Only the Redis storage boundary can be a test double. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(Qa7HttpSupport.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:qa7;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false",
        "jwt.secret-key=qa7-synthetic-signing-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=qa7-unused", "pg.toss.secret-key=qa7-unused",
        "app.payment.workers-enabled=false"
})
abstract class Qa7HttpSupport {
    static final String SECRET = "qa7-synthetic-signing-key-at-least-32-bytes";
    static final String PASSWORD = "Qa7-synthetic-password";
    static final long REFRESH_TTL = 1_209_600_000L;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final MemoryRedis MEMORY = new MemoryRedis();
    static boolean realRedis() { return System.getenv("QA_TASK7_REDIS_PORT") != null; }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry properties) {
        // Never fall back to the developer's Redis instance for actual storage operations.
        String port = System.getenv("QA_TASK7_REDIS_PORT");
        if (port != null && (Integer.parseInt(port) == 6379 || System.getenv("QA_TASK7_REDIS_PASSWORD") == null))
            throw new IllegalStateException("QA7 requires an isolated non-6379 Redis and an ephemeral password");
        properties.add("spring.data.redis.host", () -> "127.0.0.1");
        properties.add("spring.data.redis.port", () -> realRedis() ? Integer.parseInt(port) : 1);
        properties.add("spring.data.redis.password", () -> realRedis() ? System.getenv("QA_TASK7_REDIS_PASSWORD") : "qa7-unused");
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary
        RedisTemplate<String, String> qa7Redis(@Qualifier("redisTemplate") RedisTemplate<String, String> actual) {
            return realRedis() ? actual : MEMORY.template;
        }
    }

    /** An expiring key/value boundary; production RefreshTokenService is never mocked. */
    static final class MemoryRedis {
        record Entry(String value, long deadline) {}
        final Map<String, Entry> entries = new ConcurrentHashMap<>();
        final AtomicLong offset = new AtomicLong();
        final RedisTemplate<String, String> template;
        long now() { return System.currentTimeMillis() + offset.get(); }
        Entry entry(String key) {
            Entry entry = entries.get(key);
            if (entry != null && entry.deadline <= now()) { entries.remove(key, entry); return null; }
            return entry;
        }
        @SuppressWarnings("unchecked")
        MemoryRedis() {
            template = mock(RedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(values);
            doAnswer(call -> {
                entries.put(call.getArgument(0), new Entry(call.getArgument(1), now()
                        + ((TimeUnit) call.getArgument(3)).toMillis(call.getArgument(2)))); return null;
            }).when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
            when(values.get(anyString())).thenAnswer(call -> {
                Entry entry = entry(call.getArgument(0)); return entry == null ? null : entry.value;
            });
            when(template.delete(anyString())).thenAnswer(call -> entries.remove((String) call.getArgument(0)) != null);
            when(template.getExpire(anyString(), any(TimeUnit.class))).thenAnswer(call -> {
                Entry entry = entry(call.getArgument(0));
                return entry == null ? -2L : ((TimeUnit) call.getArgument(1)).convert(entry.deadline - now(), TimeUnit.MILLISECONDS);
            });
            when(template.expire(anyString(), anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
                String key = call.getArgument(0); Entry entry = entry(key);
                if (entry == null) return false;
                entries.put(key, new Entry(entry.value, now() + ((TimeUnit) call.getArgument(2)).toMillis(call.getArgument(1))));
                return true;
            });
        }
    }

    @Autowired MemberRepository members;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtUtil jwt;
    @Autowired RefreshTokenService refreshTokens;
    @Autowired @Qualifier("qa7Redis") RedisTemplate<String, String> redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @LocalServerPort int port;

    String unique() { return UUID.randomUUID().toString(); }
    Member member(Role role) {
        String id = unique();
        return members.saveAndFlush(Member.builder().email(id + "@test.invalid").password(passwords.encode(PASSWORD))
                .name("QA7 합성 회원").nickname(id).role(role).build());
    }
    void tx(Runnable action) { new TransactionTemplate(manager).executeWithoutResult(status -> action.run()); }
    void status(Long id, MemberStatus status) { tx(() -> members.findById(id).orElseThrow().updateStatus(status)); }
    String access(Member member) { return jwt.generateAccessToken(member.getId(), member.getRole()); }
    String json(Object body) { return JSON.writeValueAsString(body); }
    HttpResponse<String> request(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : json(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    JsonNode response(HttpResponse<String> response, int expected) {
        // Never include a token-bearing response body in assertion failures / XML.
        assertEquals(expected, response.statusCode(), "HTTP 응답 상태");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        JsonNode root = JSON.readTree(response.body());
        assertEquals(expected, root.path("status").asInt());
        assertTrue(root.path("message").isTextual());
        assertTrue(root.has("data")); assertTrue(root.has("errors"));
        return root;
    }
    JsonNode data(HttpResponse<String> response, int expected) { return response(response, expected).path("data"); }
    void error(HttpResponse<String> response, int expected, String message) {
        JsonNode root = response(response, expected);
        assertEquals(message, root.path("message").asText()); assertTrue(root.path("data").isNull());
    }
    JsonNode login(Member member) throws Exception {
        return data(request("POST", "/api/v1/auth/login", Map.of("email", member.getEmail(), "password", PASSWORD), null), 200);
    }
    HttpResponse<String> reissue(String refresh) throws Exception {
        return request("POST", "/api/v1/auth/reissue", Map.of("refreshToken", refresh), null);
    }
    String signed(Long id, boolean expired, boolean wrongKey) {
        long now = System.currentTimeMillis();
        return Jwts.builder().subject(id.toString()).issuedAt(new Date(now - 120_000))
                .expiration(new Date(now + (expired ? -60_000 : 60_000)))
                .signWith(Keys.hmacShaKeyFor((wrongKey ? SECRET + "other" : SECRET).getBytes(StandardCharsets.UTF_8))).compact();
    }
    String key(Member member) { return "refresh" + member.getId(); }
    void sameToken(String expected, String actual) { assertTrue(Objects.equals(expected, actual), "저장 토큰 일치 (원문 생략)"); }
}

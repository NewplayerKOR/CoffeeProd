package com.back.coffeeprod.dev.auth;

import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import com.back.coffeeprod.global.security.auth.CustomUserDetails;
import com.back.coffeeprod.global.security.auth.CustomUserDetailsService;
import com.back.coffeeprod.global.security.handler.RestAuthenticationEntryPoint;
import com.back.coffeeprod.global.security.jwt.*;
import com.back.coffeeprod.global.security.service.RefreshTokenService;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevTask9JwtVerificationTest {
    static final String SECRET = "dev9-jwt-synthetic-signing-secret-at-least-32-bytes";
    static final long ACCESS = 1_800_000L, REFRESH = 1_209_600_000L;
    static boolean baseline() { return "true".equals(System.getenv("DEV_TASK9_7_BASELINE")); }
    JwtUtil jwt() { return new JwtUtil(SECRET, ACCESS, REFRESH); }
    Member member(long id) {
        var result = Member.builder().email("synthetic@test.invalid").password("unused").nickname("synthetic")
                .name("합성 회원").role(Role.USER).build(); ReflectionTestUtils.setField(result, "id", id); return result;
    }
    CustomUserDetailsService users() {
        var result = mock(CustomUserDetailsService.class);
        when(result.loadUserByUsername(anyString())).thenAnswer(call -> new CustomUserDetails(member(Long.parseLong(call.getArgument(0)))));
        return result;
    }
    void filter(JwtUtil jwt, String token, jakarta.servlet.FilterChain chain) throws Exception {
        filter(jwt, users(), token, chain);
    }
    void filter(JwtUtil jwt, CustomUserDetailsService users, String token, jakarta.servlet.FilterChain chain) throws Exception {
        applyFilter(new JwtAuthenticationFilter(jwt, users, mock(RestAuthenticationEntryPoint.class)), token, chain);
    }
    void applyFilter(JwtAuthenticationFilter filter, String token, jakarta.servlet.FilterChain chain) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var request = new MockHttpServletRequest("GET", "/api/v1/carts");
            if (token != null) request.addHeader("Authorization", "Bearer " + token);
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        } finally { SecurityContextHolder.clearContext(); }
    }
    MemberService service(JwtUtil jwt, long id, String refresh) {
        var repository = mock(MemberRepository.class); when(repository.findById(id)).thenReturn(Optional.of(member(id)));
        var storage = mock(RefreshTokenService.class);
        when(storage.isValid(id, refresh)).thenReturn(true);
        return new MemberService(repository, mock(PasswordEncoder.class), jwt, storage);
    }
    MemberDto.RefreshRequest request(String token) {
        var result = new MemberDto.RefreshRequest(); ReflectionTestUtils.setField(result, "refreshToken", token); return result;
    }
    void counts(JwtParserProbe probe, int tokens) {
        assertEquals(baseline() ? 0 : 1, probe.initializationBuilds);
        assertEquals(baseline() ? 2 * tokens : 0, probe.builds.get());
        assertEquals((baseline() ? 2 : 1) * tokens, probe.parses.get());
    }

    @Test void filterVerifiesOneTokenOnce() throws Exception {
        try (var probe = new JwtParserProbe()) {
            var jwt = probe.initialize(this::jwt); String token = jwt.generateAccessToken(7L, Role.USER);
            filter(jwt, token, (request, response) -> assertEquals("7", SecurityContextHolder.getContext().getAuthentication().getName()));
            counts(probe, 1);
            System.out.printf("DEV9_JWT_COUNTS scope=filter baseline=%s init=%d builds=%d parses=%d%n", baseline(), probe.initializationBuilds, probe.builds.get(), probe.parses.get());
        }
    }
    @Test void reissueVerifiesOneTokenOnce() {
        try (var probe = new JwtParserProbe()) {
            var jwt = probe.initialize(this::jwt); String token = jwt.generateRefreshToken(7L);
            var response = service(jwt, 7, token).reissue(request(token)); assertNotNull(response.getAccessToken());
            assertFalse(token.equals(response.getRefreshToken()), "rotated token differs (contents omitted)"); counts(probe, 1);
            System.out.printf("DEV9_JWT_COUNTS scope=reissue baseline=%s init=%d builds=%d parses=%d%n", baseline(), probe.initializationBuilds, probe.builds.get(), probe.parses.get());
        }
    }
    @Test void authorizationAndRefreshBodyAreEachVerifiedOnce() throws Exception {
        try (var probe = new JwtParserProbe()) {
            var jwt = probe.initialize(this::jwt); String header = jwt.generateAccessToken(9L, Role.USER), body = jwt.generateRefreshToken(7L);
            var service = service(jwt, 7, body);
            filter(jwt, header, (request, response) -> {
                assertEquals("9", SecurityContextHolder.getContext().getAuthentication().getName());
                assertNotNull(service.reissue(request(body)).getAccessToken());
            });
            counts(probe, 2);
            System.out.printf("DEV9_JWT_COUNTS scope=header-and-body baseline=%s init=%d builds=%d parses=%d%n", baseline(), probe.initializationBuilds, probe.builds.get(), probe.parses.get());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"malformed", "empty", "null", "unsigned", "expired", "wrong-key"})
    void invalidTokensKeepBooleanAndStrictApiBehavior(String kind) {
        var jwt = jwt(); String token = switch (kind) {
            case "empty" -> ""; case "null" -> null; case "malformed" -> "invalid";
            case "unsigned" -> Jwts.builder().subject("7").compact();
            case "expired" -> new JwtUtil(SECRET, -60000, -60000).generateAccessToken(7L, Role.USER);
            default -> new JwtUtil(SECRET + "different", ACCESS, REFRESH).generateAccessToken(7L, Role.USER);
        };
        assertFalse(jwt.validateToken(token)); assertThrows(RuntimeException.class, () -> jwt.getMemberIdFromToken(token));
    }
    @Test void expirationIsRecheckedWithoutCachingClaims() {
        long now = 1_800_000_000_000L; var clock = new AtomicLong(now);
        try (var probe = new JwtParserProbe(() -> new Date(clock.get()))) {
            var jwt = probe.initialize(this::jwt);
            String token = Jwts.builder().subject("7").issuedAt(new Date(now)).expiration(new Date(now + 10000))
                    .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
            assertTrue(jwt.validateToken(token)); assertEquals(7L, jwt.getMemberIdFromToken(token));
            clock.addAndGet(20000); assertFalse(jwt.validateToken(token));
            assertThrows(ExpiredJwtException.class, () -> jwt.getMemberIdFromToken(token));
        }
    }
    @Test void legacyTokenWithoutJtiAndStrictSubjectConversionRemainCompatible() {
        var jwt = jwt(); String token = Jwts.builder().subject("7").expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertTrue(jwt.validateToken(token)); assertEquals(7L, jwt.getMemberIdFromToken(token));
        String malformedSubject = Jwts.builder().subject("not-a-number").signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertTrue(jwt.validateToken(malformedSubject)); assertThrows(NumberFormatException.class, () -> jwt.getMemberIdFromToken(malformedSubject));
    }
    @Test void invalidRefreshStillRaisesInvalidToken() {
        var failure = assertThrows(CustomException.class, () -> service(jwt(), 7, "expected").reissue(request("invalid")));
        assertEquals(ErrorCode.INVALID_TOKEN, failure.getErrorCode());
    }
    @Test void claimsApiReturnsFreshVerifiedPayloads() {
        var jwt = jwt(); String token = jwt.generateAccessToken(7L, Role.USER);
        var first = jwt.validateAndGetClaims(token).orElseThrow();
        var second = jwt.validateAndGetClaims(token).orElseThrow();
        assertNotSame(first, second); assertEquals("7", second.getSubject()); assertEquals("USER", second.get("role"));
        assertTrue(jwt.validateAndGetClaims("invalid").isEmpty());
        assertTrue(jwt.validateAndGetClaims(null).isEmpty());
        assertTrue(jwt.validateAndGetClaims(new JwtUtil(SECRET, -60000, REFRESH).generateAccessToken(7L, Role.USER)).isEmpty());
    }
    @Test void sharedParserDoesNotMixConcurrentMemberIdentities() throws Exception {
        var jwt = jwt(); var users = users();
        try (var pool = Executors.newFixedThreadPool(20)) {
            var futures = new ArrayList<Future<?>>();
            for (long id = 1; id <= 200; id++) {
                long expected = id; String token = jwt.generateAccessToken(id, Role.USER);
                futures.add(pool.submit(() -> { filter(jwt, users, token, (request, response) -> assertEquals(Long.toString(expected), SecurityContextHolder.getContext().getAuthentication().getName())); return null; }));
            }
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
    }
    @Test void uninstrumentedFilterCpuSamples() throws Exception {
        var jwt = jwt(); var users = users(); String token = jwt.generateAccessToken(7L, Role.USER);
        var filter = new JwtAuthenticationFilter(jwt, users, mock(RestAuthenticationEntryPoint.class));
        var cpu = ManagementFactory.getThreadMXBean(); boolean available = cpu.isCurrentThreadCpuTimeSupported();
        if (available && !cpu.isThreadCpuTimeEnabled()) cpu.setThreadCpuTimeEnabled(true);
        jakarta.servlet.FilterChain chain = (request, response) -> { if (SecurityContextHolder.getContext().getAuthentication() == null) throw new AssertionError("missing authentication"); };
        for (int i = 0; i < 1000; i++) applyFilter(filter, token, chain);
        for (int sample = 1; sample <= 3; sample++) {
            long before = available ? cpu.getCurrentThreadCpuTime() : -1, wall = System.nanoTime();
            for (int i = 0; i < 10000; i++) applyFilter(filter, token, chain);
            long elapsed = System.nanoTime() - wall, cpuNanos = available ? cpu.getCurrentThreadCpuTime() - before : -1;
            System.out.printf("DEV9_JWT_CPU baseline=%s sample=%d calls=10000 cpuNanos=%d elapsedNanos=%d%n", baseline(), sample, cpuNanos, elapsed);
        }
    }
}

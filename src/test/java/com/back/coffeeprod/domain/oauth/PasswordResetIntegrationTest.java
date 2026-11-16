package com.back.coffeeprod.domain.oauth;

import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import com.back.coffeeprod.global.security.service.RefreshTokenService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PasswordResetIntegrationTest.Config.class)
@TestPropertySource(properties={"spring.jpa.show-sql=false","spring.jpa.open-in-view=false","spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate",
    "jwt.secret-key=password-reset-synthetic-signing-key-at-least-32-bytes","jwt.access-expiration=1800000","jwt.refresh-expiration=1209600000",
    "pg.toss.client-key=unused","pg.toss.secret-key=unused","AUTH_VERIFICATION_SECRET=reset-synthetic-verification-secret-32-bytes"})
@Testcontainers @Tag("oauth-infra")
class PasswordResetIntegrationTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container static final GenericContainer<?> POSTGRES=new GenericContainer<>("postgres:16-alpine").withEnv("POSTGRES_PASSWORD","synthetic").withExposedPorts(5432);
    static final ObjectMapper JSON=new ObjectMapper();
    static final Map<String,String> CODES=new ConcurrentHashMap<>();
    static volatile MailSender.Outcome outcome=MailSender.Outcome.SENT;
    @TestConfiguration static class Config {
        @Bean @Primary MailSender fakeMail(){return (email,code,key)->{CODES.put(email,code);return outcome;};}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/postgres");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");p.add("spring.datasource.username",()->"postgres");p.add("spring.datasource.password",()->"synthetic");
        p.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
        p.add("spring.data.redis.host",REDIS::getHost);p.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));p.add("spring.data.redis.password",()->"");
    }
    @LocalServerPort int port;
    @Autowired MemberRepository members; @Autowired PasswordEncoder passwords;
    @Autowired JwtUtil jwt; @org.springframework.test.context.bean.override.mockito.MockitoSpyBean RefreshTokenService refresh;
    @Autowired AuthStore store; @Autowired EmailVerificationService verification;
    @Autowired org.springframework.data.redis.connection.RedisConnectionFactory redis;
    @BeforeEach void reset() { outcome=MailSender.Outcome.SENT; try(var c=redis.getConnection()){c.serverCommands().flushDb();} }
    void clearCooldown(String email,String tx) {store.eval("redis.call('DEL',KEYS[1]); return 'OK'",List.of("auth:mail:cooldown:"+verification.digest(email)));}
    String codeKey(String email,String tx) {return "auth:mail:code:"+verification.digest("PASSWORD_RESET:"+email.toLowerCase(Locale.ROOT)+":"+tx);}
    void expire(String key) {store.eval("redis.call('DEL',KEYS[1]); return 'OK'",List.of(key));}
    String unique(){return UUID.randomUUID().toString().replace("-","");}
    Member member(){return members.saveAndFlush(Member.builder().email(unique()+"@example.test").password(passwords.encode("OldPassword123!")).name("회원").nickname(unique()).build());}
    HttpResponse<String> post(String path,Object body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/"+path))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(HttpResponse<String> response){assertEquals(200,response.statusCode(),response.body());return JSON.readTree(response.body()).path("data");}
    String send(String email) throws Exception {return ok(post("password-reset/send",Map.of("email",email))).path("transaction").asText();}
    String code(String email) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<until){if(CODES.containsKey(email)){Thread.sleep(100);return CODES.get(email);}Thread.sleep(20);}throw new AssertionError("모의 메일 미발송");
    }
    String verify(String email,String tx) throws Exception {return ok(post("password-reset/verify",Map.of("email",email,"transaction",tx,"code",code(email)))).path("grant").asText();}
    Map<String,Object> resetBody(String email,String tx,String grant){return Map.of("email",email,"transaction",tx,"grant",grant,"newPassword","NewPassword123!","confirmPassword","NewPassword123!");}
    int me(String token) throws Exception {return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/members/me")).header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofString()).statusCode();}
    @Test void normalResetRevokesBothTokensAndRequiresNewLogin() throws Exception {
        Member m=member();String access=jwt.generateAccessToken(m.getId(),m.getRole()),r=jwt.generateRefreshToken(m.getId());refresh.save(m.getId(),r);
        assertEquals(200,me(access));String tx=send(m.getEmail()),grant=verify(m.getEmail(),tx);
        ok(post("password-reset/reset",resetBody(m.getEmail(),tx,grant)));
        assertEquals(401,me(access));assertEquals(401,post("reissue",Map.of("refreshToken",r)).statusCode());
        assertEquals(401,post("login",Map.of("email",m.getEmail(),"password","OldPassword123!")).statusCode());
        var tokens=ok(post("login",Map.of("email",m.getEmail(),"password","NewPassword123!")));
        assertEquals(200,me(tokens.path("accessToken").asText()));ok(post("reissue",Map.of("refreshToken",tokens.path("refreshToken").asText())));
        assertEquals(400,post("password-reset/reset",resetBody(m.getEmail(),tx,grant)).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"missing","social","suspended","withdrawn"})
    void ineligibleAccountsHaveSameInitialContractAndNoMail(String kind) throws Exception {
        Member m=member();if(kind.equals("social"))m.updatePassword(null);if(kind.equals("suspended"))m.updateStatus(MemberStatus.SUSPENDED);if(kind.equals("withdrawn"))m.updateStatus(MemberStatus.WITHDRAWN);members.saveAndFlush(m);
        String email=kind.equals("missing")?unique()+"@example.test":m.getEmail();String tx=send(email);
        assertTrue(tx.matches("[A-Za-z0-9_-]{43}"));Thread.sleep(150);
        assertFalse(CODES.containsKey(email));assertEquals(400,post("password-reset/verify",Map.of("email",email,"transaction",tx,"code","123456")).statusCode());
        if(kind.equals("social"))assertNull(members.findById(m.getId()).orElseThrow().getPassword());
    }
    @Test void bindingWrongCodeAndAttemptLimit() throws Exception {
        Member m=member();String tx=send(m.getEmail()),code=code(m.getEmail());
        assertEquals(400,post("password-reset/verify",Map.of("email","another@example.test","transaction",tx,"code",code)).statusCode());
        for(int i=0;i<5;i++)assertEquals(400,post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",code.equals("000000")?"000001":"000000")).statusCode());
        assertEquals(429,post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",code)).statusCode());
    }
    @Test void concurrentVerifyAndResetAreSingleUse() throws Exception {
        Member m=member();String tx=send(m.getEmail()),code=code(m.getEmail());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",code)));
            var b=pool.submit(()->post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",code)));
            var ra=a.get();var rb=b.get();assertEquals(List.of(200,400),java.util.stream.Stream.of(ra.statusCode(),rb.statusCode()).sorted().toList());
            String grant=ok(ra.statusCode()==200?ra:rb).path("grant").asText();
            var c=pool.submit(()->post("password-reset/reset",resetBody(m.getEmail(),tx,grant)));var d=pool.submit(()->post("password-reset/reset",resetBody(m.getEmail(),tx,grant)));
            assertEquals(List.of(200,400),java.util.stream.Stream.of(c.get().statusCode(),d.get().statusCode()).sorted().toList());
        }
    }
    @Test void mismatchedPasswordAndEmailDoNotConsumeValidGrant() throws Exception {
        Member m=member();String tx=send(m.getEmail()),grant=verify(m.getEmail(),tx);
        var body=new HashMap<>(resetBody(m.getEmail(),tx,grant));body.put("confirmPassword","DifferentPassword!");
        assertEquals(400,post("password-reset/reset",body).statusCode());
        body=new HashMap<>(resetBody(m.getEmail(),tx,grant));body.put("email","other@example.test");assertEquals(400,post("password-reset/reset",body).statusCode());
        body=new HashMap<>(resetBody(m.getEmail(),tx,grant));body.put("newPassword","가".repeat(25));body.put("confirmPassword","가".repeat(25));assertEquals(400,post("password-reset/reset",body).statusCode());
        ok(post("password-reset/reset",resetBody(m.getEmail(),tx,grant)));
    }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN","REJECTED"})
    void failedMailNeverActivatesNewCode(String result) throws Exception {
        outcome=MailSender.Outcome.valueOf(result);Member m=member();String tx=send(m.getEmail()),code=code(m.getEmail());
        assertEquals(400,post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",code)).statusCode());
        assertTrue(passwords.matches("OldPassword123!",members.findById(m.getId()).orElseThrow().getPassword()));
    }
    @Test void statusRecheckedAfterVerification() throws Exception {
        Member m=member();String tx=send(m.getEmail()),grant=verify(m.getEmail(),tx);m.updateStatus(MemberStatus.SUSPENDED);members.saveAndFlush(m);
        assertEquals(400,post("password-reset/reset",resetBody(m.getEmail(),tx,grant)).statusCode());
    }
    @Test void expiredGrantAndCrossPurposeAreRejected() throws Exception {
        Member m=member();String tx=send(m.getEmail()),grant=verify(m.getEmail(),tx);
        store.consume("emailgrant:"+grant);assertEquals(400,post("password-reset/reset",resetBody(m.getEmail(),tx,grant)).statusCode());
        String oauth=AuthStore.random();verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,m.getEmail()+".oauth",oauth,"cross-purpose");
        String foreign=verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,m.getEmail()+".oauth",oauth,CODES.get(m.getEmail()+".oauth"));
        assertEquals(400,post("password-reset/reset",resetBody(m.getEmail(),tx,foreign)).statusCode());
    }
    @Test void resendReplacesOldCodeOnlyAfterSuccess() throws Exception {
        Member m=member();String tx=send(m.getEmail()),old=code(m.getEmail());
        clearCooldown(m.getEmail(),tx);CODES.remove(m.getEmail());
        ok(post("password-reset/send",Map.of("email",m.getEmail(),"transaction",tx)));
        String fresh=code(m.getEmail());
        if(!old.equals(fresh)) assertEquals(400,post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",old)).statusCode());
        assertFalse(ok(post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",fresh))).path("grant").asText().isBlank());
    }
    @Test void failedResendPreservesPreviousCodeAndQuota() throws Exception {
        Member m=member();String tx=send(m.getEmail()),old=code(m.getEmail());clearCooldown(m.getEmail(),tx);CODES.remove(m.getEmail());outcome=MailSender.Outcome.UNKNOWN;
        ok(post("password-reset/send",Map.of("email",m.getEmail(),"transaction",tx)));code(m.getEmail());
        assertFalse(ok(post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",old))).path("grant").asText().isBlank());
    }
    @Test void expiredCodeAndTransactionCannotResetPassword() throws Exception {
        Member m=member();String tx=send(m.getEmail());code(m.getEmail());expire(codeKey(m.getEmail(),tx));
        assertEquals(400,post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",CODES.get(m.getEmail()))).statusCode());
        expire("oauth:tx:"+tx);assertEquals(400,post("password-reset/send",Map.of("email",m.getEmail(),"transaction",tx)).statusCode());
    }
    @Test void outstandingGrantCannotResetAgainAfterAnotherSuccessfulReset() throws Exception {
        Member m=member();String first=send(m.getEmail()),g1=verify(m.getEmail(),first);
        clearCooldown(m.getEmail(),first);CODES.remove(m.getEmail());String second=send(m.getEmail()),g2=verify(m.getEmail(),second);
        ok(post("password-reset/reset",resetBody(m.getEmail(),first,g1)));
        assertEquals(400,post("password-reset/reset",resetBody(m.getEmail(),second,g2)).statusCode());
    }
    @Test void fiveFailuresAndEmailFailureBudgetSurviveResend() throws Exception {
        Member m=member();String tx=send(m.getEmail()),correct=code(m.getEmail());String wrong=correct.equals("000000")?"999999":"000000";
        for(int n=0;n<5;n++)assertEquals(400,post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",wrong)).statusCode());
        assertEquals(429,post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",correct)).statusCode());
        clearCooldown(m.getEmail(),tx);CODES.remove(m.getEmail());ok(post("password-reset/send",Map.of("email",m.getEmail(),"transaction",tx)));correct=code(m.getEmail());wrong=correct.equals("000000")?"999999":"000000";
        for(int n=0;n<5;n++)assertEquals(400,post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",wrong)).statusCode());
        assertEquals(429,post("password-reset/verify",Map.of("email",m.getEmail(),"transaction",tx,"code",correct)).statusCode());
    }
    @Test void resendCooldownDoesNotReplaceCurrentCode() throws Exception {
        Member m=member();String tx=send(m.getEmail()),old=code(m.getEmail());ok(post("password-reset/send",Map.of("email",m.getEmail(),"transaction",tx)));Thread.sleep(150);
        assertEquals(old,CODES.get(m.getEmail()));assertFalse(verify(m.getEmail(),tx).isBlank());
    }
    @Test void legacyTokensWorkUntilFirstResetAndVersionedTokensRemainValid() throws Exception {
        Member m=member();String legacy=io.jsonwebtoken.Jwts.builder().subject(m.getId().toString()).claim("role","USER").expiration(new Date(System.currentTimeMillis()+60000))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor("password-reset-synthetic-signing-key-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        assertEquals(200,me(legacy));String tx=send(m.getEmail()),grant=verify(m.getEmail(),tx);ok(post("password-reset/reset",resetBody(m.getEmail(),tx,grant)));assertEquals(401,me(legacy));
        assertEquals(401,me(jwt.generateAccessToken(m.getId(),m.getRole(),0)));assertEquals(200,me(jwt.generateAccessToken(m.getId(),m.getRole(),1)));
    }

    @Test void codeGrantAndTransactionTtlsMatchPolicy() throws Exception {
        Member m=member();String tx=send(m.getEmail());code(m.getEmail());
        long codeTtl=Long.parseLong(store.eval("return tostring(redis.call('PTTL',KEYS[1]))",List.of(codeKey(m.getEmail(),tx))));
        long txTtl=Long.parseLong(store.eval("return tostring(redis.call('PTTL',KEYS[1]))",List.of("oauth:tx:"+tx)));
        assertTrue(codeTtl>290000 && codeTtl<=300000);assertTrue(txTtl>590000 && txTtl<=600000);
        String grant=verify(m.getEmail(),tx);
        long grantTtl=Long.parseLong(store.eval("return tostring(redis.call('PTTL',KEYS[1]))",List.of("auth:emailgrant:"+grant)));
        assertTrue(grantTtl>290000 && grantTtl<=300000);
    }

    @Test void redisFailureDuringTokenRevocationRollsBackPasswordChange() throws Exception {
        Member m=member();String access=jwt.generateAccessToken(m.getId(),m.getRole());String tx=send(m.getEmail()),grant=verify(m.getEmail(),tx);
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("synthetic outage")).when(refresh).delete(m.getId());
        assertEquals(503,post("password-reset/reset",resetBody(m.getEmail(),tx,grant)).statusCode());
        Member preserved=members.findById(m.getId()).orElseThrow();assertTrue(passwords.matches("OldPassword123!",preserved.getPassword()));assertEquals(0,preserved.getTokenVersion());assertEquals(200,me(access));
        assertEquals(400,post("password-reset/reset",resetBody(m.getEmail(),tx,grant)).statusCode());
    }

}

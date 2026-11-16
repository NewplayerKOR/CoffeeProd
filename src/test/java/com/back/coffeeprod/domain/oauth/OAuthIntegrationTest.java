package com.back.coffeeprod.domain.oauth;

import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import com.back.coffeeprod.global.security.service.RefreshTokenService;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OAuthIntegrationTest.Config.class)
@TestPropertySource(properties={"spring.jpa.show-sql=false","spring.jpa.open-in-view=false","spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate",
    "jwt.secret-key=oauth-synthetic-signing-key-at-least-32-bytes","jwt.access-expiration=1800000","jwt.refresh-expiration=1209600000",
    "pg.toss.client-key=unused","pg.toss.secret-key=unused","OAUTH_GOOGLE_CLIENT_ID=google-test","OAUTH_GOOGLE_CLIENT_SECRET=unused",
    "OAUTH_KAKAO_CLIENT_ID=kakao-test","OAUTH_KAKAO_CLIENT_SECRET=unused","AUTH_VERIFICATION_SECRET=oauth-synthetic-verification-secret-32-bytes"})
@Testcontainers
@Tag("oauth-infra")
class OAuthIntegrationTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    @Container static final GenericContainer<?> POSTGRES=new GenericContainer<>(DockerImageName.parse("postgres:16-alpine")).withEnv("POSTGRES_PASSWORD","synthetic").withEnv("POSTGRES_DB","oauth").withExposedPorts(5432);
    static final ObjectMapper JSON=new ObjectMapper();
    static final Map<String,Scenario> SCENARIOS=new ConcurrentHashMap<>();
    static final FakeMail MAIL=new FakeMail();
    static final RSAKey KEY; static final HttpServer PROVIDER;
    record Scenario(String provider,String sub,String email,boolean verified,String nonce,boolean badAudience) { }
    static final Map<String,String> FAULTS=new ConcurrentHashMap<>();
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("test-key").generate();
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PROVIDER.createContext("/jwks",e->{byte[] body=JSON.writeValueAsBytes(new JWKSet(KEY.toPublicJWK()).toJSONObject());e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
            PROVIDER.createContext("/token",e->{try {
                var form=query(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String code=form.get("code");var s=SCENARIOS.get(code);
                if(s==null){e.sendResponseHeaders(400,-1);e.close();return;}
                var result=new HashMap<String,Object>();result.put("access_token",code);result.put("token_type","Bearer");result.put("expires_in",300);
                if(s.provider.equals("google")) {
                    String fault=FAULTS.getOrDefault(s.email,"");
                    var claims=new JWTClaimsSet.Builder().issuer(fault.equals("issuer")?"https://wrong.example.test":base()).subject(s.sub).audience(s.badAudience?"wrong-client":"google-test")
                        .issueTime(Date.from(Instant.now().minusSeconds(900))).expirationTime(Date.from(Instant.now().plusSeconds(fault.equals("expiry")?-600:300)))
                        .claim("nonce",fault.equals("nonce")?"wrong-nonce":s.nonce).claim("auth_time",Instant.now().getEpochSecond()).claim("email",s.email).claim("email_verified",s.verified).build();
                    var signed=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),claims);
                    signed.sign(new RSASSASigner(fault.equals("signature")?new RSAKeyGenerator(2048).generate():KEY));result.put("id_token",signed.serialize());result.put("scope","openid email profile");
                }
                byte[] body=JSON.writeValueAsBytes(result);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            }catch(Exception failure){e.sendResponseHeaders(500,-1);e.close();}});
            PROVIDER.createContext("/user",e->{
                String token=e.getRequestHeaders().getFirst("Authorization");var s=SCENARIOS.get(token==null?"":token.substring(7));
                Map<String,Object> user=s.provider.equals("google")?Map.of("sub",s.sub,"email",s.email,"email_verified",s.verified):Map.of("id",Long.parseLong(s.sub),"kakao_account",Map.of("email",s.email,"is_email_valid",s.verified,"is_email_verified",s.verified));
                byte[] body=JSON.writeValueAsBytes(user);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            });
            PROVIDER.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static String base(){return "http://127.0.0.1:"+PROVIDER.getAddress().getPort();}
    static Map<String,String> query(String value){var r=new HashMap<String,String>();for(String item:value.split("&")){String[] parts=item.split("=",2);r.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length>1?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");}return r;}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/oauth");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");p.add("spring.datasource.username",()->"postgres");p.add("spring.datasource.password",()->"synthetic");
        p.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
        p.add("spring.data.redis.host",REDIS::getHost);p.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));p.add("spring.data.redis.password",()->"");
    }
    @TestConfiguration static class Config {
        @Bean @Primary MailSender fakeMail(){return MAIL;}
        @Bean @Primary ClientRegistrationRepository testRegistrations() {
            return id->{if(!Set.of("google","kakao").contains(id))return null;
                var b=ClientRegistration.withRegistrationId(id).clientId(id+"-test").clientSecret("unused")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/"+id).authorizationUri(base()+"/authorize").tokenUri(base()+"/token").userInfoUri(base()+"/user").userNameAttributeName(id.equals("google")?"sub":"id");
                if(id.equals("google"))b.scope("openid","email","profile").jwkSetUri(base()+"/jwks").issuerUri(base());else b.scope("profile_nickname");
                return b.build();};
        }
    }
    static class FakeMail implements MailSender {
        final Map<String,String> codes=new ConcurrentHashMap<>();volatile Outcome outcome=Outcome.SENT;
        public Outcome send(String email,String code,String key){codes.put(email,code);return outcome;}
    }
    @LocalServerPort int port;
    @Autowired AuthStore store;@Autowired OAuthAccountService service;@Autowired EmailVerificationService verification;
    @Autowired OAuthSettings settings;
    @Autowired MemberRepository members;@Autowired SocialAccountRepository accounts;@Autowired PasswordEncoder encoder;@Autowired JwtUtil jwt;@Autowired RefreshTokenService refresh;
    @BeforeEach void reset(){MAIL.outcome=MailSender.Outcome.SENT;}
    @AfterAll static void stopProvider(){PROVIDER.stop(0);}
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();}
    HttpResponse<String> request(HttpClient browser,String method,String path,Object body,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(20)).header("Origin","http://localhost:3000").header("X-OAuth-Request","1").header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));return browser.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(HttpResponse<String> r){assertEquals(200,r.statusCode(),r.body());return JSON.readTree(r.body()).path("data");}
    String unique(){return UUID.randomUUID().toString().replace("-","");}
    String email(){return unique()+"@example.test";}
    String subject(String provider){return provider.equals("google")?unique():""+Math.abs(UUID.randomUUID().getMostSignificantBits());}
    Map<String,String> authorize(HttpClient b,String provider,String sub,String email,boolean verified,Map<String,Object> start,String token,boolean badAudience) throws Exception {
        ok(request(b,"POST","/api/v1/auth/oauth/"+provider+"/start",start,token));
        var redirect=request(b,"GET","/oauth2/authorization/"+provider,null,null);assertEquals(302,redirect.statusCode(),redirect.body());
        var params=query(URI.create(redirect.headers().firstValue("Location").orElseThrow()).getRawQuery());String code=unique();SCENARIOS.put(code,new Scenario(provider,sub,email,verified,params.get("nonce"),badAudience));
        var callback=request(b,"GET","/login/oauth2/code/"+provider+"?state="+params.get("state")+"&code="+code,null,null);
        assertEquals(302,callback.statusCode(),callback.body());assertEquals("http://localhost:3000/auth/callback",callback.headers().firstValue("Location").orElseThrow());
        return params;
    }
    JsonNode completeSignup(HttpClient b,String email,String grant) throws Exception {return ok(request(b,"POST","/api/v1/auth/oauth/signup",Map.of("email",email,"name","테스트회원","nickname",unique(),"grant",grant),null));}
    String token(Member m){return jwt.generateAccessToken(m.getId(),m.getRole());}
    Member local(){return members.saveAndFlush(Member.builder().email(email()).password(encoder.encode("Password123!")).name("회원").nickname(unique()).build());}

    @ParameterizedTest @ValueSource(strings={"google","kakao"})
    void signupLoginReplayAndWithdrawnIdentity(String provider) throws Exception {
        String email=email(),subject=subject(provider);var b=browser();authorize(b,provider,subject,email,true,Map.of("purpose","LOGIN"),null,false);
        assertEquals("SIGNUP",ok(request(b,"POST","/api/v1/auth/oauth/exchange",Map.of(),null)).path("status").asText());
        JsonNode tokens=completeSignup(b,email,"");String access=tokens.path("accessToken").asText();assertFalse(access.isBlank());
        assertEquals(400,request(b,"POST","/api/v1/auth/oauth/signup",Map.of("email",email,"name","회원","nickname",unique()),null).statusCode());
        assertFalse(ok(request(b,"GET","/api/v1/members/me",null,access)).path("hasLocalPassword").asBoolean());
        authorize(b,provider,subject,email,true,Map.of("purpose","LOGIN"),null,false);
        var login=ok(request(b,"POST","/api/v1/auth/oauth/exchange",Map.of(),null));assertEquals("LOGIN",login.path("status").asText());
        assertEquals(400,request(b,"POST","/api/v1/auth/oauth/exchange",Map.of(),null).statusCode());
        authorize(b,provider,subject,email,true,Map.of("purpose","WITHDRAW"),access,false);
        assertEquals("WITHDRAW_CONFIRM",ok(request(b,"POST","/api/v1/auth/oauth/exchange",Map.of(),access)).path("status").asText());
        ok(request(b,"POST","/api/v1/auth/oauth/withdraw",Map.of("confirmed",true),access));
        assertEquals(401,request(b,"GET","/api/v1/members/me",null,access).statusCode());
        authorize(b,provider,subject,email,true,Map.of("purpose","LOGIN"),null,false);
        assertEquals(401,request(b,"POST","/api/v1/auth/oauth/exchange",Map.of(),null).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"google","kakao"})
    void explicitLocalLinkAndConflict(String provider) throws Exception {
        Member member=local();String subject=subject(provider),access=token(member);var b=browser();
        authorize(b,provider,subject,member.getEmail(),true,Map.of("purpose","LOGIN"),null,false);
        assertEquals(409,request(b,"POST","/api/v1/auth/oauth/signup",Map.of("email",member.getEmail(),"name","회원","nickname",unique()),null).statusCode());
        assertEquals(401,request(b,"POST","/api/v1/auth/oauth/"+provider+"/start",Map.of("purpose","LINK","currentPassword","wrong"),access).statusCode());
        authorize(b,provider,subject,member.getEmail(),true,Map.of("purpose","LINK","currentPassword","Password123!"),access,false);
        assertEquals(400,request(b,"POST","/api/v1/auth/oauth/link",Map.of("confirmed",false),access).statusCode());
        ok(request(b,"POST","/api/v1/auth/oauth/link",Map.of("confirmed",true),access));
        var other=local();authorize(b,provider,subject,member.getEmail(),true,Map.of("purpose","LINK","currentPassword","Password123!"),token(other),false);
        assertEquals(409,request(b,"POST","/api/v1/auth/oauth/link",Map.of("confirmed",true),token(other)).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"google","kakao"})
    void missingOrChangedEmailNeedsPurposeBoundVerification(String provider) throws Exception {
        var b=browser();String email=email();authorize(b,provider,subject(provider),"",false,Map.of("purpose","LOGIN"),null,false);
        assertEquals(403,request(b,"POST","/api/v1/auth/oauth/signup",Map.of("email",email,"name","회원","nickname",unique()),null).statusCode());
        ok(request(b,"POST","/api/v1/auth/oauth/email/send",Map.of("email",email),null));
        assertEquals(429,request(b,"POST","/api/v1/auth/oauth/email/send",Map.of("email",email),null).statusCode());
        String grant=ok(request(b,"POST","/api/v1/auth/oauth/email/verify",Map.of("email",email,"code",MAIL.codes.get(email)),null)).path("grant").asText();
        assertFalse(completeSignup(b,email,grant).path("accessToken").asText().isBlank());
    }
    @Test void invalidGoogleAudienceNeverIssuesTokens() throws Exception {
        var b=browser();authorize(b,"google",subject("google"),email(),true,Map.of("purpose","LOGIN"),null,true);
        assertEquals(400,request(b,"POST","/api/v1/auth/oauth/exchange",Map.of(),null).statusCode());
    }
    @Test void originAndCsrfHeaderAreRequired() throws Exception {
        var response=browser().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/oauth/google/start")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"purpose\":\"LOGIN\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(403,response.statusCode());
        assertEquals(401,request(browser(),"POST","/api/v1/auth/oauth/google/start",Map.of("purpose","LINK"),null).statusCode());
    }
    @Test void purposeEmailTransactionAndOneTimeGrant() {
        String email=email(),tx=AuthStore.random();verification.send(EmailVerificationService.Purpose.PASSWORD_RESET,email,tx,"purpose-ip-"+unique());
        assertThrows(CustomException.class,()->verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,MAIL.codes.get(email)));
        assertThrows(CustomException.class,()->verification.verify(EmailVerificationService.Purpose.PASSWORD_RESET,email,AuthStore.random(),MAIL.codes.get(email)));
        String grant=verification.verify(EmailVerificationService.Purpose.PASSWORD_RESET,email,tx,MAIL.codes.get(email));
        assertThrows(CustomException.class,()->verification.consume(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,grant));
        assertThrows(CustomException.class,()->verification.consume(EmailVerificationService.Purpose.PASSWORD_RESET,email,tx,grant));
    }
    @Test void callbackClaimIsAtomic() throws Exception {
        String id=AuthStore.random();store.create(id,Map.of("status","READY"),600);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var results=pool.invokeAll(java.util.stream.IntStream.range(0,20).<Callable<Boolean>>mapToObj(i->()->store.transition(id,"READY","DONE")).toList());
            int wins=0;for(var r:results)if(r.get())wins++;assertEquals(1,wins);
        }
    }
    @ParameterizedTest @ValueSource(strings={"issuer","expiry","nonce","signature"})
    void invalidGoogleClaimsAreRejected(String fault) throws Exception {
        var b=browser();String email=email();FAULTS.put(email,fault);
        authorize(b,"google",subject("google"),email,true,Map.of("purpose","LOGIN"),null,false);
        assertEquals(400,request(b,"POST","/api/v1/auth/oauth/exchange",Map.of(),null).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"google","kakao"})
    void suspendedAccountCannotLogin(String provider) throws Exception {
        var m=local();m.updateStatus(MemberStatus.SUSPENDED);members.saveAndFlush(m);
        String subject=subject(provider);accounts.saveAndFlush(new SocialAccount(m,provider,AuthStore.hash(provider+":"+subject)));
        var b=browser();authorize(b,provider,subject,m.getEmail(),true,Map.of("purpose","LOGIN"),null,false);
        assertEquals(403,request(b,"POST","/api/v1/auth/oauth/exchange",Map.of(),null).statusCode());
    }
    @Test void socialOnlyLinkRequiresOwnedReauthentication() throws Exception {
        var b=browser();String subject=subject("google"),email=email();authorize(b,"google",subject,email,true,Map.of("purpose","LOGIN"),null,false);
        String access=completeSignup(b,email,"").path("accessToken").asText();
        assertEquals(403,request(b,"POST","/api/v1/auth/oauth/kakao/start",Map.of("purpose","LINK"),access).statusCode());
        authorize(b,"google",subject,email,true,Map.of("purpose","LINK","reauthenticate",true),access,false);
        String proof=ok(request(b,"POST","/api/v1/auth/oauth/exchange",Map.of(),access)).path("confirmation").asText();assertFalse(proof.isBlank());
        authorize(b,"kakao",subject("kakao"),email,true,Map.of("purpose","LINK","confirmation",proof),access,false);
        ok(request(b,"POST","/api/v1/auth/oauth/link",Map.of("confirmed",true),access));
        assertEquals(403,request(b,"POST","/api/v1/auth/oauth/kakao/start",Map.of("purpose","LINK","confirmation",proof),access).statusCode());
    }
    @Test void anotherMembersProviderCannotConfirmWithdrawal() throws Exception {
        var m=local();String sub=subject("google");accounts.saveAndFlush(new SocialAccount(m,"google",AuthStore.hash("google:"+sub)));
        var b=browser();authorize(b,"google",subject("google"),email(),true,Map.of("purpose","WITHDRAW"),token(m),false);
        assertEquals(403,request(b,"POST","/api/v1/auth/oauth/withdraw",Map.of("confirmed",true),token(m)).statusCode());
        assertEquals(MemberStatus.ACTIVE,members.findById(m.getId()).orElseThrow().getStatus());
    }
    @Test void expiredConfirmationAndTransactionAreRejected() throws Exception {
        Member m=local();var tx=new HashMap<>(Map.of("memberId",m.getId().toString(),"confirmationExpires","0"));
        assertThrows(CustomException.class,()->service.checkTarget(tx,m.getId()));
        String id=AuthStore.random();store.create(id,Map.of("status","READY"),1);Thread.sleep(1100);
        assertFalse(store.transition(id,"READY","DONE"));
    }
    @Test void fiveFailuresBlockCorrectCodeAndResendCannotResetEmailFailures() {
        String email=email(),tx=AuthStore.random();verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,"fail-"+unique());
        String correct=MAIL.codes.get(email),wrong=correct.equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++)assertThrows(CustomException.class,()->verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,wrong));
        assertThrows(CustomException.class,()->verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,correct));
        String failKey="auth:mail:fail:"+verification.digest(email);
        assertEquals("5",store.eval("return tostring(redis.call('ZCARD',KEYS[1]))",List.of(failKey)));
    }
    void clearCooldown(String email) {store.eval("redis.call('DEL',KEYS[1]); return 'OK'",List.of("auth:mail:cooldown:"+verification.digest(email)));}
    @Test void resendInvalidatesOldCodeWithoutResettingFailureHistory() {
        String email=email(),tx=AuthStore.random();verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,"resend-"+unique());String previous=MAIL.codes.get(email);
        clearCooldown(email);verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,"resend-"+unique());String next=MAIL.codes.get(email);
        // 난수 충돌은 가능하므로 두 코드가 같으면 코드 교체 대신 일회성 소비를 검증한다.
        if(!previous.equals(next))assertThrows(CustomException.class,()->verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,previous));
        String grant=verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,next);
        verification.consume(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,grant);
        assertThrows(CustomException.class,()->verification.consume(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,grant));
    }
    @Test void unknownDeliveryPreservesPreviousCodeAndReservation() {
        String email=email(),tx=AuthStore.random();verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,"unknown-"+unique());String previous=MAIL.codes.get(email);
        clearCooldown(email);MAIL.outcome=MailSender.Outcome.UNKNOWN;
        assertThrows(CustomException.class,()->verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,"unknown-"+unique()));
        assertFalse(verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,previous).isBlank());
        assertEquals("2",store.eval("return tostring(redis.call('ZCARD',KEYS[1]))",List.of("auth:mail:eh:"+verification.digest(email))));
    }
    @Test void mailProviderCanChangeWithoutChangingVerificationContract() {
        String email=email(),tx=AuthStore.random();var alternate=new FakeMail();
        var changed=new EmailVerificationService(store,alternate,settings);
        changed.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,"alternate-"+unique());
        String grant=verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,alternate.codes.get(email));
        verification.consume(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,grant);
    }
    @Test void emailAndIpSendLimitsAreEnforced() {
        String email=email(),tx=AuthStore.random();
        for(int i=0;i<3;i++){clearCooldown(email);verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,"limit-"+unique());}
        clearCooldown(email);assertThrows(CustomException.class,()->verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,"limit-"+unique()));
        String ip="ip-limit-"+unique();for(int i=0;i<10;i++)verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email(),AuthStore.random(),ip);
        assertThrows(CustomException.class,()->verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email(),AuthStore.random(),ip));
    }
    @Test void localLoginPasswordChangeLogoutAndRefreshStillWork() throws Exception {
        var m=local();var b=browser();var login=ok(request(b,"POST","/api/v1/auth/login",Map.of("email",m.getEmail(),"password","Password123!"),null));String access=login.path("accessToken").asText();
        ok(request(b,"PATCH","/api/v1/members/me/password",Map.of("currentPassword","Password123!","newPassword","NextPassword123!"),access));
        assertEquals(401,request(b,"POST","/api/v1/auth/login",Map.of("email",m.getEmail(),"password","Password123!"),null).statusCode());
        var next=ok(request(b,"POST","/api/v1/auth/reissue",Map.of("refreshToken",login.path("refreshToken").asText()),null));
        ok(request(b,"POST","/api/v1/auth/logout",Map.of(),next.path("accessToken").asText()));
        assertEquals(401,request(b,"POST","/api/v1/auth/reissue",Map.of("refreshToken",next.path("refreshToken").asText()),null).statusCode());
        ok(request(b,"POST","/api/v1/auth/login",Map.of("email",m.getEmail(),"password","NextPassword123!"),null));
    }
    @Test void invalidStateAndMissingBrowserCookieCannotAuthorize() throws Exception {
        var b=browser();ok(request(b,"POST","/api/v1/auth/oauth/google/start",Map.of("purpose","LOGIN"),null));
        var redirect=request(b,"GET","/oauth2/authorization/google",null,null);
        var params=query(URI.create(redirect.headers().firstValue("Location").orElseThrow()).getRawQuery());
        String code=unique();SCENARIOS.put(code,new Scenario("google",subject("google"),email(),true,params.get("nonce"),false));
        assertEquals(302,request(browser(),"GET","/login/oauth2/code/google?state="+params.get("state")+"&code="+code,null,null).statusCode());
        assertEquals(302,request(b,"GET","/login/oauth2/code/google?state=wrong&code="+code,null,null).statusCode());
        assertEquals(400,request(b,"POST","/api/v1/auth/oauth/exchange",Map.of(),null).statusCode());
    }
    @Test void changedVerifiedEmailStillRequiresVerification() throws Exception {
        var b=browser();authorize(b,"google",subject("google"),email(),true,Map.of("purpose","LOGIN"),null,false);
        assertEquals(403,request(b,"POST","/api/v1/auth/oauth/signup",Map.of("email",email(),"name","회원","nickname",unique()),null).statusCode());
    }
    @Test void concurrentVerificationIssuesOnlyOneGrant() throws Exception {
        String email=email(),tx=AuthStore.random();verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,"concurrent-"+unique());String code=MAIL.codes.get(email);
        try(var pool=Executors.newFixedThreadPool(5)) {
            var results=pool.invokeAll(java.util.stream.IntStream.range(0,10).<Callable<Boolean>>mapToObj(i->()->{try{verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,code);return true;}catch(CustomException e){return false;}}).toList());
            int wins=0;for(var r:results)if(r.get())wins++;assertEquals(1,wins);
        }
    }
    @Test void expiredCodeIsRejected() {
        String email=email(),tx=AuthStore.random();verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,"expiry-"+unique());
        String bind=verification.digest("OAUTH_SIGNUP_EMAIL:"+email+":"+tx);
        store.eval("redis.call('EXPIRE',KEYS[1],0); return 'OK'",List.of("auth:mail:code:"+bind));
        assertThrows(CustomException.class,()->verification.verify(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,tx,MAIL.codes.get(email)));
    }
    @Test void globalDailyAndMonthlyLimitsAreEnforced() {
        String daily="auth:mail:all:"+java.time.LocalDate.now(java.time.ZoneOffset.UTC),monthly="auth:mail:month:"+java.time.YearMonth.now(java.time.ZoneOffset.UTC);
        for(String key:List.of(daily,monthly)) {
            int count=key.equals(daily)?80:2400;
            try {
                store.eval("for i=1,tonumber(ARGV[1]) do redis.call('ZADD',KEYS[1],ARGV[2],'limit-test-'..i) end; return 'OK'",List.of(key),""+count,""+System.currentTimeMillis());
                assertThrows(CustomException.class,()->verification.send(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email(),AuthStore.random(),"global-"+unique()));
            }finally {store.eval("for i=1,tonumber(ARGV[1]) do redis.call('ZREM',KEYS[1],'limit-test-'..i) end; return 'OK'",List.of(key),""+count);}
        }
    }
}

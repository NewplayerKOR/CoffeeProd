package com.back.coffeeprod.qa.recommendation;

import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeProfileDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.*;
import com.back.coffeeprod.domain.coffeeprofile.repository.*;
import com.back.coffeeprod.domain.coffeeprofile.service.CoffeeProfileService;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.product.repository.*;
import com.back.coffeeprod.domain.recommendation.dto.MemberCoffeePreferenceDto;
import com.back.coffeeprod.domain.recommendation.repository.MemberCoffeePreferenceRepository;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** An independent database, real security/HTTP/services, and synthetic fixtures only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:qa8;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false", "spring.flyway.enabled=false",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password=qa8-unused",
        "jwt.secret-key=qa8-synthetic-signing-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=qa8-unused", "pg.toss.secret-key=qa8-unused", "app.payment.workers-enabled=false"
})
abstract class Qa8Support {
    static final String PREFERENCE = "/api/v1/members/me/coffee-preference";
    static final String RECOMMEND = "/api/v1/coffee-recommendations";
    static final String ADMIN_PROFILES = "/api/v1/admin/coffee-profiles";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    // These tables exist only in this dedicated in-memory database. Keep FK checks enabled.
    static final List<String> TABLES = List.of("member_coffee_preference", "product", "coffee_profile_flavor_note",
            "coffee_profile_brew_method", "coffee_profile_variety", "coffee_profile_component", "coffee_profile",
            "flavor_note", "brew_method", "coffee_variety", "processing_method", "category", "member");
    @Autowired MemberRepository members;
    @Autowired MemberCoffeePreferenceRepository preferences;
    @Autowired CoffeeProfileRepository profiles;
    @Autowired CoffeeProfileService profileService;
    @Autowired ProcessingMethodRepository methods;
    @Autowired FlavorNoteRepository flavors;
    @Autowired BrewMethodRepository brews;
    @Autowired CoffeeVarietyRepository varieties;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired JwtUtil jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager entityManager;
    @LocalServerPort int port;
    Member owner, admin;

    @BeforeEach void resetIsolatedFixtures() {
        for (String table : TABLES) jdbc.update("delete from " + table);
        owner = member(Role.USER); admin = member(Role.ADMIN);
    }
    String unique() { return UUID.randomUUID().toString(); }
    Member member(Role role) {
        String id = unique();
        return members.saveAndFlush(Member.builder().email(id + "@test.invalid").password("qa8-no-password-login")
                .name("QA8 합성 회원").nickname(id).role(role).build());
    }
    String token(Member member) { return jwt.generateAccessToken(member.getId(), member.getRole()); }
    void status(Member member, MemberStatus status) {
        new TransactionTemplate(manager).executeWithoutResult(s -> members.findById(member.getId()).orElseThrow().updateStatus(status));
    }
    HttpResponse<String> http(String verb, String route, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.method(verb, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode root(HttpResponse<String> result, int expected) {
        assertEquals(expected, result.statusCode(), "HTTP 상태 (인증 원문 출력 금지)");
        assertTrue(result.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        var root = JSON.readTree(result.body());
        // Existing creation contract: HTTP 201 with CommonResponse.success status 200.
        assertEquals(expected == 201 ? 200 : expected, root.path("status").asInt());
        assertTrue(root.path("message").isTextual()); assertTrue(root.has("data")); assertTrue(root.has("errors"));
        return root;
    }
    JsonNode data(HttpResponse<String> result, int expected) { return root(result, expected).path("data"); }
    void error(HttpResponse<String> result, int expected, String message) {
        var root = root(result, expected); assertTrue(root.path("data").isNull());
        assertEquals(message, root.path("message").asText());
    }
    JsonNode tree(Object value) { return JSON.readTree(JSON.writeValueAsString(value)); }
    void assertProfileStored(JsonNode response, JsonNode stored) {
        // H2 TIMESTAMP(6) rounds nanoseconds to the nearest microsecond on persistence.
        // Compare every field; normalize only this documented storage representation.
        assertEquals(storagePrecision(response), storagePrecision(stored), "전체 프로필 값과 DB 정밀도의 감사 시각 일치");
    }
    private JsonNode storagePrecision(JsonNode value) {
        var normalized = (tools.jackson.databind.node.ObjectNode) tree(value);
        for (String field : List.of("createdAt", "updatedAt")) {
            var instant = java.time.OffsetDateTime.parse(value.path(field).asText()).toInstant();
            normalized.put(field, instant.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        }
        return normalized;
    }
    Map<String,Object> single() {
        var result = new LinkedHashMap<String,Object>();
        result.put("profileName", unique()); result.put("beanType", "SINGLE_ORIGIN");
        result.put("originCountryCode", "BR"); result.put("roastLevel", "MEDIUM"); result.put("decaf", false);
        for (String field : List.of("acidity","body","sweetness","aroma")) result.put(field, 3);
        for (String field : List.of("flavorNotes","brewMethods","varieties","components")) result.put(field, List.of());
        return result;
    }
    Map<String,Object> component(String country, String ratio) {
        var result = new LinkedHashMap<String,Object>(); result.put("originCountryCode", country);
        if (ratio != null) result.put("componentRatio", new java.math.BigDecimal(ratio));
        return result;
    }
    Map<String,Object> blend() {
        var result = single(); result.put("beanType", "BLEND"); result.remove("originCountryCode");
        result.put("components", List.of(component("BR","60.00"), component("CO","40.00"))); return result;
    }
    Long profile(Map<String,Object> request) {
        return profileService.createCoffeeProfile(JSON.convertValue(request, CoffeeProfileDto.Request.class)).getId();
    }
    JsonNode profileView(Long id) {
        return new TransactionTemplate(manager).execute(s -> {
            entityManager.flush(); entityManager.clear();
            return tree(new CoffeeProfileDto.Response(profiles.findById(id).orElseThrow()));
        });
    }
    JsonNode preferenceView(Member member) {
        return new TransactionTemplate(manager).execute(s -> {
            entityManager.flush(); entityManager.clear();
            return tree(new MemberCoffeePreferenceDto.Response(preferences.findByMemberId(member.getId()).orElseThrow()));
        });
    }
    Long method() {
        return methods.saveAndFlush(ProcessingMethod.builder().code(unique()).name("QA8 가공 방식").description("합성").build()).getId();
    }
    Long reference(String field) {
        return switch (field) {
            case "flavorNotes" -> flavors.saveAndFlush(FlavorNote.builder().code(unique()).name("합성 향미").description("QA8").build()).getId();
            case "brewMethods" -> brews.saveAndFlush(BrewMethod.builder().code(unique()).name("합성 추출법").description("QA8").build()).getId();
            case "varieties" -> varieties.saveAndFlush(CoffeeVariety.builder().code(unique()).name("합성 품종").description("QA8").build()).getId();
            default -> throw new IllegalArgumentException(field);
        };
    }
    Map<String,Object> link(String field, Long id) {
        return switch(field) {
            case "flavorNotes" -> new LinkedHashMap<>(Map.of("flavorNoteId",id,"intensity",3));
            case "brewMethods" -> new LinkedHashMap<>(Map.of("brewMethodId",id,"recommendationNote","합성 안내"));
            case "varieties" -> new LinkedHashMap<>(Map.of("coffeeVarietyId",id));
            default -> throw new IllegalArgumentException(field);
        };
    }
    Product product(Long profile, int price, int stock, ProductStatus status) {
        var category = categories.saveAndFlush(Category.builder().name(unique()).build());
        var result = products.saveAndFlush(Product.builder().category(category).coffeeProfile(profile == null ? null : profiles.findById(profile).orElseThrow())
                .sku(unique()).weightGrams(200).name("QA8 합성 원두").price(price).stockQuantity(stock).roastLevel(RoastLevel.MEDIUM)
                .description("합성").imageUrl("https://assets-coffeeprod.ttagyulab.com/products/catalog/so001-l-v1.webp").build());
        new TransactionTemplate(manager).executeWithoutResult(s -> products.findById(result.getId()).orElseThrow().updateStatus(status));
        return result;
    }
    Map<String,Object> state() {
        var result = new LinkedHashMap<String,Object>();
        for(String table:TABLES) result.put(table, jdbc.queryForList("select * from " + table + " order by 1"));
        return result;
    }
    void unchanged(Map<String,Object> previous) { assertTrue(previous.equals(state()), "거부/읽기 후 저장 행과 값 보존"); }
}

package com.back.coffeeprod.qa.catalog;

import com.back.coffeeprod.domain.coffeeprofile.entity.BrewMethod;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeProfile;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeVariety;
import com.back.coffeeprod.domain.coffeeprofile.entity.FlavorNote;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.product.entity.Category;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password=unused",
        "jwt.secret-key=profile-update-test-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=unused", "pg.toss.secret-key=unused"
})
class CoffeeProfileUpdateHttpTest {
    private static final String PROFILES = "/api/v1/admin/coffee-profiles";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String postgresUrl = System.getenv("PROFILE_UPDATE_TEST_PG_URL");
        if (postgresUrl == null) {
            registry.add("spring.datasource.url",
                    () -> "jdbc:h2:mem:profile-update;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
            return;
        }
        if (!postgresUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{4,5}/coffee_profile_check")) {
            throw new IllegalArgumentException("전용 로컬 PostgreSQL 테스트 DB가 필요합니다.");
        }
        registry.add("spring.datasource.url", () -> postgresUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "profile_check");
        registry.add("spring.datasource.password", () -> System.getenv("PROFILE_UPDATE_TEST_PG_PASSWORD"));
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @LocalServerPort int port;

    private Member admin;
    private Member user;
    private long profileId;
    private long productId;
    private long flavorFirst, flavorSecond, brewFirst, brewSecond, varietyFirst, varietySecond;
    private Map<String, Object> original;

    @BeforeEach
    void prepareProfileWithExistingRelations() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            admin = member(Role.ADMIN);
            user = member(Role.USER);
            flavorFirst = flavor();
            flavorSecond = flavor();
            brewFirst = brewMethod();
            brewSecond = brewMethod();
            varietyFirst = variety();
            varietySecond = variety();
        });
        original = new LinkedHashMap<>(Map.of(
                "profileName", "검증 블렌드", "beanType", "BLEND", "roastLevel", "MEDIUM", "decaf", false,
                "acidity", 3, "body", 4, "sweetness", 4, "aroma", 3));
        original.put("flavorNotes", List.of(flavor(flavorFirst, 2), flavor(flavorSecond, 3)));
        original.put("brewMethods", List.of(brew(brewFirst, "첫 추출 안내"), brew(brewSecond, "둘째 추출 안내")));
        original.put("varieties", List.of(variety(varietyFirst), variety(varietySecond)));
        original.put("components", List.of(component("ET", 40), component("CO", 60)));
        profileId = data("POST", PROFILES, original, admin, 201).path("id").longValue();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var category = persist(Category.builder().name(code()).build());
            var profile = entityManager.getReference(CoffeeProfile.class, profileId);
            var product = persist(Product.builder().category(category).coffeeProfile(profile).sku(code()).weightGrams(200)
                    .name("연결 상품").price(10000).stockQuantity(3).roastLevel(RoastLevel.MEDIUM).build());
            productId = product.getId();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedRelationsKeepTheirRows(boolean rename) throws Exception {
        var before = relationIds();
        if (rename) original.put("profileName", "이름만 변경한 블렌드");
        putAndReload(original);
        assertEquals(before, relationIds());
        assertProductLink();
    }

    @Test
    void replacesOnlyGroupsThatChangedAndKeepsRequestedOrder() throws Exception {
        var before = relationIds();
        original.put("flavorNotes", List.of(flavor(flavorSecond, 4), flavor(flavorFirst, 2)));
        original.put("brewMethods", List.of(brew(brewSecond, "수정 안내"), brew(brewFirst, "첫 추출 안내")));
        original.put("varieties", List.of(variety(varietySecond), variety(varietyFirst)));
        var changed = putAndReload(original);
        assertEquals(before.get(3), relationIds().get(3));
        assertEquals(List.of(flavorSecond, flavorFirst), ids(changed, "flavorNotes", "flavorNoteId"));
        assertEquals(List.of(brewSecond, brewFirst), ids(changed, "brewMethods", "brewMethodId"));
        assertEquals(List.of(varietySecond, varietyFirst), ids(changed, "varieties", "coffeeVarietyId"));
        assertEquals(4, changed.at("/flavorNotes/0/intensity").intValue());
        assertEquals("수정 안내", changed.at("/brewMethods/0/recommendationNote").asText());

        var beforeComponents = relationIds();
        original.put("components", List.of(component("CO", 55), component("BR", 45)));
        var blend = putAndReload(original);
        assertEquals(beforeComponents.subList(0, 3), relationIds().subList(0, 3));
        assertEquals("CO", blend.at("/components/0/originCountryCode").asText());
        assertEquals(55, blend.at("/components/0/componentRatio").decimalValue().intValueExact());
        assertEquals("BR", blend.at("/components/1/originCountryCode").asText());
        assertEquals(45, blend.at("/components/1/componentRatio").decimalValue().intValueExact());
        for (String group : List.of("flavor_note", "brew_method", "variety", "component")) {
            assertEquals(List.of(1, 2), jdbc.queryForList("select display_order from coffee_profile_" + group
                    + " where coffee_profile_id = ? order by display_order", Integer.class, profileId));
        }
        assertProductLink();
    }

    @Test
    void rejectsUnauthorizedAndInvalidUpdatesWithoutSaving() throws Exception {
        var before = data("GET", profilePath(), null, admin, 200);
        var rows = relationIds();
        original.put("profileName", "저장되면 안 되는 이름");
        data("PUT", profilePath(), original, null, 401);
        data("PUT", profilePath(), original, user, 403);
        original.put("varieties", List.of(variety(Long.MAX_VALUE)));
        data("PUT", profilePath(), original, admin, 404);
        assertEquals(before, data("GET", profilePath(), null, admin, 200));
        assertEquals(rows, relationIds());
        assertProductLink();
    }

    @Test
    void rollsBackDeletedRowsWhenReplacementInsertFails() throws Exception {
        var before = data("GET", profilePath(), null, admin, 200);
        var rows = relationIds();
        jdbc.execute("alter table coffee_profile_flavor_note add constraint qa_reject_intensity "
                + "check (coffee_profile_id <> " + profileId + " or intensity <> 5)");
        try {
            original.put("profileName", "롤백해야 하는 이름");
            original.put("flavorNotes", List.of(flavor(flavorFirst, 5), flavor(flavorSecond, 3)));
            data("PUT", profilePath(), original, admin, 500);
        } finally {
            jdbc.execute("alter table coffee_profile_flavor_note drop constraint qa_reject_intensity");
        }
        assertEquals(before, data("GET", profilePath(), null, admin, 200));
        assertEquals(rows, relationIds());
        assertProductLink();
    }

    private JsonNode putAndReload(Map<String, Object> body) throws Exception {
        var saved = (ObjectNode) data("PUT", profilePath(), body, admin, 200);
        var reloaded = (ObjectNode) data("GET", profilePath(), null, admin, 200);
        assertEquals(body.get("profileName"), reloaded.path("profileName").asText());
        var savedTime = OffsetDateTime.parse(saved.path("updatedAt").asText());
        var reloadedTime = OffsetDateTime.parse(reloaded.path("updatedAt").asText());
        assertTrue(Duration.between(savedTime, reloadedTime).abs().toNanos() <= 1_000);
        assertEquals(comparableProfile(saved), comparableProfile(reloaded));
        return reloaded;
    }

    private ObjectNode comparableProfile(ObjectNode profile) {
        var comparable = profile.deepCopy();
        comparable.remove("updatedAt");
        // JSON의 정수와 PostgreSQL NUMERIC의 소수 표기를 같은 값으로 비교한다.
        for (JsonNode item : comparable.path("components")) {
            var component = (ObjectNode) item;
            var ratio = component.path("componentRatio");
            if (ratio.isNumber()) component.put("componentRatio", ratio.decimalValue().stripTrailingZeros());
        }
        return comparable;
    }

    private void assertProductLink() {
        Long linkedProfile = jdbc.queryForObject("select coffee_profile_id from product where product_id = ?", Long.class, productId);
        assertNotNull(linkedProfile);
        assertEquals(profileId, linkedProfile.longValue());
    }

    private List<List<Long>> relationIds() {
        return List.of("flavor_note", "brew_method", "variety", "component").stream()
                .map(table -> jdbc.queryForList("select coffee_profile_" + table + "_id from coffee_profile_" + table
                        + " where coffee_profile_id = ? order by display_order", Long.class, profileId))
                .toList();
    }

    private List<Long> ids(JsonNode profile, String group, String idField) {
        return profile.path(group).valueStream().map(item -> item.path(idField).longValue()).toList();
    }

    private String profilePath() {
        return PROFILES + "/" + profileId;
    }

    private JsonNode data(String method, String path, Object body, Member actor, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (actor != null) request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor.getId(), actor.getRole()));
        var result = HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, result.statusCode(), method + " " + path);
        return JSON.readTree(result.body()).path("data");
    }

    private Member member(Role role) {
        String code = code();
        return persist(Member.builder().email(code + "@test.invalid").nickname(code).name("프로필 검증 계정")
                .password("not-used-for-login").role(role).build());
    }

    private long flavor() { return persist(FlavorNote.builder().code(code()).name("향미").build()).getId(); }
    private long brewMethod() { return persist(BrewMethod.builder().code(code()).name("추출법").build()).getId(); }
    private long variety() { return persist(CoffeeVariety.builder().code(code()).name("품종").build()).getId(); }
    private String code() { return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT); }
    private <T> T persist(T row) { entityManager.persist(row); return row; }
    private Map<String, Object> flavor(long id, int intensity) { return Map.of("flavorNoteId", id, "intensity", intensity); }
    private Map<String, Object> brew(long id, String note) { return Map.of("brewMethodId", id, "recommendationNote", note); }
    private Map<String, Object> variety(long id) { return Map.of("coffeeVarietyId", id); }
    private Map<String, Object> component(String country, int ratio) {
        return Map.of("originCountryCode", country, "originRegion", country + " 산지", "componentRatio", ratio);
    }
}

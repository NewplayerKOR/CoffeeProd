package com.back.coffeeprod.dev.recommendation;

import com.back.coffeeprod.domain.coffeeprofile.entity.*;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.recommendation.dto.CoffeeRecommendationDto;
import com.back.coffeeprod.domain.recommendation.entity.MemberCoffeePreference;
import com.back.coffeeprod.domain.recommendation.service.CoffeeRecommendationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic fixtures in a dedicated H2 database or an explicitly owned localhost PostgreSQL. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.default_batch_fetch_size=100",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.back.coffeeprod.dev.recommendation.DevTask9RecommendationLoadingIntegrationTest$SnapshotBarrier",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password=dev9-unused",
        "jwt.secret-key=dev9-synthetic-signing-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=dev9-unused", "pg.toss.secret-key=dev9-unused", "app.payment.workers-enabled=false",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class DevTask9RecommendationLoadingIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> REASONS = List.of("선호 로스팅 단계와 일치", "선호 원두 유형과 일치",
            "산미 선호와 일치", "바디 선호와 일치", "단맛 선호와 일치", "향 선호와 일치");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("DEV_TASK9_1_PG_URL");
        boolean postgres = url != null;
        if (postgres) {
            var match = java.util.regex.Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:(\\d+)/devtask9_1_[a-z0-9_]+")
                    .matcher(url);
            if (!match.matches() || Integer.parseInt(match.group(1)) <= 1024
                    || Integer.parseInt(match.group(1)) > 65535 || Integer.parseInt(match.group(1)) == 5432
                    || System.getenv("DEV_TASK9_1_PG_PASSWORD") == null) {
                throw new IllegalArgumentException("DEV9-1 requires a dedicated localhost non-5432 PostgreSQL");
            }
            UUID.fromString(System.getenv("DEV_TASK9_1_OWNER"));
        }
        registry.add("spring.datasource.url", () -> postgres ? url
                : "jdbc:h2:mem:devtask9_1;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        registry.add("spring.datasource.driver-class-name", () -> postgres ? "org.postgresql.Driver" : "org.h2.Driver");
        registry.add("spring.datasource.username", () -> postgres ? "devtask9" : "sa");
        registry.add("spring.datasource.password", () -> postgres ? System.getenv("DEV_TASK9_1_PG_PASSWORD") : "");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> postgres ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> postgres ? "validate" : "create-drop");
        registry.add("spring.flyway.enabled", () -> postgres);
    }

    @Autowired ProductRepository products;
    @Autowired CoffeeRecommendationService recommendations;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        SnapshotBarrier.ACTIVE.set(null);
        for (String table : List.of("member_coffee_preference", "product", "coffee_profile_flavor_note",
                "coffee_profile_brew_method", "coffee_profile_variety", "coffee_profile_component",
                "coffee_profile", "category", "member")) {
            jdbc.update("delete from " + table);
        }
    }

    <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    CoffeeRecommendationDto.Request request(Integer limit) {
        var values = new java.util.LinkedHashMap<String, Object>(Map.of("roastLevel", "MEDIUM", "beanType", "BLEND",
                "preferredAcidity", 3, "preferredBody", 3, "preferredSweetness", 3, "preferredAroma", 3));
        if (limit != null) values.put("limit", limit);
        return JSON.convertValue(values, CoffeeRecommendationDto.Request.class);
    }

    void fixture(int count) {
        tx(() -> {
            String suffix = UUID.randomUUID().toString();
            var category = Category.builder().name("DEV9 " + suffix).build(); em.persist(category);
            var method = ProcessingMethod.builder().code("D9-" + suffix).name("가공 방식").build(); em.persist(method);
            var flavor = FlavorNote.builder().code("D9-" + suffix).name("향미").description("향미 설명").build(); em.persist(flavor);
            var brew = BrewMethod.builder().code("D9-" + suffix).name("추출법").description("추출 설명").build(); em.persist(brew);
            var variety = CoffeeVariety.builder().code("D9-" + suffix).name("품종").description("품종 설명").build(); em.persist(variety);
            CoffeeProfile profile = null;
            for (int i = 0; i < count; i++) {
                // Adjacent products share a profile; other pairs use distinct profiles.
                if (i % 2 == 0) {
                    profile = CoffeeProfile.builder().profileName("프로필 " + i).beanType(BeanType.BLEND)
                            .processingMethod(i % 4 == 0 ? null : method).roastLevel(RoastLevel.MEDIUM)
                            .decaf(i % 4 == 0).acidity((short) 3).body((short) 3)
                            .sweetness((short) 3).aroma((short) 3).summary("프로필 설명").build();
                    profile.replaceFlavorNotes(List.of(CoffeeProfileFlavorNote.of(profile, flavor, (short) 1, (short) 4)));
                    profile.replaceBrewMethods(List.of(CoffeeProfileBrewMethod.of(profile, brew, (short) 1, "추출 안내")));
                    profile.replaceVarieties(List.of(CoffeeProfileVariety.of(profile, variety, (short) 1)));
                    profile.replaceComponents(List.of(
                            CoffeeProfileComponent.of(profile, "BR", "지역", null, new BigDecimal("60.00"), (short) 1),
                            CoffeeProfileComponent.of(profile, "CO", null, method, new BigDecimal("40.00"), (short) 2)));
                    em.persist(profile);
                }
                em.persist(Product.builder().category(category).coffeeProfile(profile).sku("D9-" + suffix + "-" + i)
                        .name("원두 " + i).price(10000 + (count - i) / 2).stockQuantity(20).weightGrams(200)
                        // Ranking uses the profile roast level, even when the product differs.
                        .roastLevel(RoastLevel.DARK).description("상품 설명")
                        .imageUrl("https://assets-coffeeprod.ttagyulab.com/products/catalog/so001-l-v1.webp").build());
            }
            return null;
        });
    }

    @Test
    void candidateQueryLoadsOnlyScalarsIncludingNullableProcessingMethod() {
        fixture(192);
        var stats = emf.unwrap(SessionFactory.class).getStatistics(); stats.clear();
        var candidates = tx(() -> products.findRecommendationCandidates(ProductStatus.ON_SALE, null));
        assertEquals(192, candidates.size());
        assertTrue(candidates.stream().anyMatch(row -> row.processingMethodId() == null));
        assertTrue(candidates.stream().anyMatch(row -> row.processingMethodId() != null));
        assertTrue(candidates.stream().allMatch(row -> row.roastLevel() == RoastLevel.MEDIUM));
        assertEquals(0, stats.getEntityLoadCount());
        assertEquals(0, stats.getCollectionLoadCount());
        assertEquals(1, stats.getPrepareStatementCount());
    }

    @ParameterizedTest
    @CsvSource({"192,5", "192,10", "1000,5", "1000,10"})
    void fullResponseMatchesLegacySelectionAndLoadsOnlySelectedProducts(int count, int limit) {
        fixture(count);
        var stats = emf.unwrap(SessionFactory.class).getStatistics(); stats.clear();
        // The old full-entity candidate query is retained only as a test reference.
        JsonNode expected = tx(() -> JSON.valueToTree(em.createQuery("""
                select p from Product p join fetch p.category join fetch p.coffeeProfile cp
                left join fetch cp.processingMethod
                where p.status = :status and p.stockQuantity > 0
                """, Product.class).setParameter("status", ProductStatus.ON_SALE).getResultList().stream()
                .sorted(Comparator.comparingInt(Product::getPrice).thenComparing(Product::getId))
                .limit(limit).map(product -> new CoffeeRecommendationDto.Response(product, 22, REASONS)).toList()));
        long legacyEntities = stats.getEntityLoadCount();
        assertEquals(count, stats.getEntityStatistics(Product.class.getName()).getLoadCount());

        stats.clear();
        var actual = recommendations.recommend(request(limit));
        long entities = stats.getEntityLoadCount(); long collections = stats.getCollectionLoadCount();
        assertEquals(expected, JSON.valueToTree(actual));
        assertEquals(limit, stats.getEntityStatistics(Product.class.getName()).getLoadCount());
        assertTrue(stats.getEntityStatistics(CoffeeProfile.class.getName()).getLoadCount() <= limit);
        assertTrue(collections <= 4L * limit);
        assertTrue(entities < legacyEntities);
        System.out.printf("DEV9_LOADING database=%s candidates=%d limit=%d legacyEntities=%d selectedEntities=%d collections=%d statements=%d%n",
                System.getenv("DEV_TASK9_1_PG_URL") == null ? "H2" : "PostgreSQL", count, limit, legacyEntities,
                entities, collections, stats.getPrepareStatementCount());
    }

    @Test
    void emptyResultSkipsDetailQuery() {
        var stats = emf.unwrap(SessionFactory.class).getStatistics(); stats.clear();
        assertTrue(recommendations.recommend(request(null)).isEmpty());
        assertEquals(1, stats.getPrepareStatementCount());
        assertEquals(0, stats.getEntityLoadCount());
        assertEquals(0, stats.getCollectionLoadCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentChangesKeepRankingAndDetailInOneSnapshot(boolean personal) throws Exception {
        fixture(1);
        Long productId = jdbc.queryForObject("select min(product_id) from product", Long.class);
        long memberId = tx(() -> {
            var member = Member.builder().email("dev9@test.invalid").password("unused").name("합성 회원")
                    .nickname(UUID.randomUUID().toString()).role(Role.USER).build(); em.persist(member);
            em.persist(MemberCoffeePreference.builder().member(member).roastLevel(RoastLevel.MEDIUM)
                    .beanType(BeanType.BLEND).preferredAcidity((short) 3).preferredBody((short) 3)
                    .preferredSweetness((short) 3).preferredAroma((short) 3).build());
            return member.getId();
        });
        var barrier = new SnapshotBarrier.Barrier(); SnapshotBarrier.ACTIVE.set(barrier);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reader = executor.submit(() -> personal ? recommendations.recommendForMember(memberId, 1)
                    : recommendations.recommend(request(1)));
            try {
                assertTrue(barrier.beforeDetail.await(15, TimeUnit.SECONDS), "candidate query must finish before update");
                tx(() -> {
                    jdbc.update("update product set price=1, status='HIDDEN', stock_quantity=0 where product_id=?", productId);
                    jdbc.update("update coffee_profile set acidity=1, roast_level='DARK'");
                    return null;
                });
            } finally {
                barrier.resume.countDown();
            }
            var result = reader.get(15, TimeUnit.SECONDS);
            assertEquals(1, result.size()); assertEquals(productId, result.getFirst().getProductId());
            assertEquals(10000, result.getFirst().getPrice()); assertEquals(22, result.getFirst().getRecommendationScore());
            assertEquals(3, result.getFirst().getAcidity()); assertEquals(RoastLevel.MEDIUM, result.getFirst().getRoastLevel());
            assertEquals(REASONS, result.getFirst().getReasons());
            assertTrue(recommendations.recommend(request(1)).isEmpty(), "next transaction sees committed unavailability");
        } finally {
            SnapshotBarrier.ACTIVE.set(null);
        }
    }

    /** Pauses only the selected-ID detail SQL, after candidate rows have been read. */
    public static class SnapshotBarrier implements StatementInspector {
        static final AtomicReference<Barrier> ACTIVE = new AtomicReference<>();
        static class Barrier {
            final CountDownLatch beforeDetail = new CountDownLatch(1);
            final CountDownLatch resume = new CountDownLatch(1);
            final AtomicBoolean claimed = new AtomicBoolean();
        }
        @Override
        public String inspect(String sql) {
            var barrier = ACTIVE.get(); String normalized = sql.toLowerCase(Locale.ROOT);
            if (barrier != null && normalized.contains("from product ") && normalized.contains("product_id in")
                    && barrier.claimed.compareAndSet(false, true)) {
                barrier.beforeDetail.countDown();
                try {
                    if (!barrier.resume.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("snapshot test barrier timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(e);
                }
            }
            return sql;
        }
    }
}

package com.back.coffeeprod.dev.indexes;

import com.back.coffeeprod.domain.coffeeprofile.entity.*;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.product.service.ProductService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Locks down the existing LIKE/optional-filter contract on H2 and an explicitly owned PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.jpa.show-sql=false", "spring.jpa.open-in-view=false",
        "app.payment.workers-enabled=false", "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.data.redis.password=unused", "jwt.secret-key=dev9-search-synthetic-secret-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000", "pg.toss.client-key=unused", "pg.toss.secret-key=unused"})
class DevTask9SearchIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        boolean pg = System.getenv("DEV_TASK9_8_PG_PREFIX") != null;
        var settings = pg ? DevTask9IndexProbe.settings() : null;
        p.add("spring.datasource.url", () -> pg ? settings.url("functional") : "jdbc:h2:mem:dev9search;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        p.add("spring.datasource.driver-class-name", () -> pg ? "org.postgresql.Driver" : "org.h2.Driver");
        p.add("spring.datasource.username", () -> pg ? "devtask9" : "sa");
        p.add("spring.datasource.password", () -> pg ? settings.password() : "");
        p.add("spring.jpa.properties.hibernate.dialect", () -> pg ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
        p.add("spring.jpa.hibernate.ddl-auto", () -> pg ? "validate" : "create-drop");
        p.add("spring.flyway.enabled", () -> pg);
    }
    @Autowired ProductService service;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    Category one, two;
    CoffeeProfile profile;
    ProcessingMethod method;
    final List<Product> products = new ArrayList<>();
    static final List<String> NAMES = List.of("에티오피아 원두", "원두 에티오피아 후반", "Coffee ABC", "coffee abc", "100% 원두", "under_score", "back\\slash", " 두 공백 ");

    @BeforeEach void fixture() {
        for (String table : List.of("payment_operation", "sales_rebuild_request", "payment", "order_item", "orders", "reviews", "product_qna", "cart_item", "cart", "address", "product", "category", "member")) jdbc.update("delete from " + table);
        products.clear();
        new TransactionTemplate(transactions).execute(s -> {
            one = Category.builder().name("첫 카테고리 " + UUID.randomUUID()).build(); em.persist(one);
            two = Category.builder().name("둘째 카테고리 " + UUID.randomUUID()).build(); em.persist(two);
            method = ProcessingMethod.builder().code("D98-" + UUID.randomUUID()).name("검증 가공법").build(); em.persist(method);
            profile = CoffeeProfile.builder().processingMethod(method).profileName("검증 프로필").beanType(BeanType.SINGLE_ORIGIN).originCountryCode("ET")
                    .roastLevel(RoastLevel.MEDIUM).decaf(true).acidity((short) 3).body((short) 3).sweetness((short) 3).aroma((short) 3).build();
            em.persist(profile);
            for (int i = 0; i < NAMES.size(); i++) {
                var product = Product.builder().category(i % 2 == 0 ? one : two).coffeeProfile(i % 2 == 0 ? profile : null)
                        .sku("D98-" + UUID.randomUUID()).weightGrams(200).name(NAMES.get(i)).price(1000 + i * 100)
                        .stockQuantity(10).roastLevel(i % 2 == 0 ? RoastLevel.MEDIUM : RoastLevel.DARK).build();
                em.persist(product); products.add(product);
            }
            products.get(6).updateStatus(ProductStatus.SOLD_OUT); products.get(7).updateStatus(ProductStatus.HIDDEN);
            return null;
        });
    }
    List<Long> ids(Page<com.back.coffeeprod.domain.product.dto.ProductDto.SummaryResponse> page) { return page.stream().map(r -> r.getId()).toList(); }
    PageRequest page() { return PageRequest.of(0, 50, Sort.by("id")); }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"에티오피아", "ABC", "abc", " ", "%", "_", "\\", "NO_MATCH"})
    void keywordRetainsCurrentLikeSemantics(String keyword) {
        // SQL oracle uses the existing explicit no-escape semantics; % and _ remain wildcards.
        List<Long> expected = keyword == null
                ? jdbc.queryForList("select product_id from product where status='ON_SALE' order by product_id", Long.class)
                : jdbc.queryForList("select product_id from product where status='ON_SALE' and name like ? escape '' order by product_id", Long.class, "%" + keyword + "%");
        var found = service.getProducts(null, null, null, null, null, null, keyword, page());
        assertEquals(expected, ids(found)); assertEquals(expected.size(), found.getTotalElements());
        if ("에티오피아".equals(keyword)) assertEquals(2, expected.size());
        if ("ABC".equals(keyword) || "abc".equals(keyword)) assertEquals(1, expected.size());
        if ("NO_MATCH".equals(keyword)) assertTrue(expected.isEmpty());
    }
    @Test void activeFiltersIntersectAndNullProfileIsPreservedWithoutProfileFilters() {
        var all = service.getProducts(null, null, null, null, null, null, null, page());
        assertEquals(6, all.getTotalElements());
        assertTrue(ids(all).contains(products.get(1).getId()));
        var matched = service.getProducts(one.getId(), profile.getId(), method.getId(), BeanType.SINGLE_ORIGIN, true, RoastLevel.MEDIUM, "원두", page());
        assertEquals(List.of(products.get(0).getId(), products.get(4).getId()), ids(matched));
        assertTrue(service.getProducts(two.getId(), profile.getId(), null, null, null, null, null, page()).isEmpty());
        assertTrue(service.getProducts(null, null, null, null, false, null, null, page()).isEmpty());
    }
    @Test void individualFiltersMatchKnownMembership() {
        List<Long> evens = List.of(products.get(0).getId(), products.get(2).getId(), products.get(4).getId());
        assertEquals(evens, ids(service.getProducts(one.getId(), null, null, null, null, null, null, page())));
        assertEquals(evens, ids(service.getProducts(null, profile.getId(), null, null, null, null, null, page())));
        assertEquals(evens, ids(service.getProducts(null, null, method.getId(), null, null, null, null, page())));
        assertEquals(evens, ids(service.getProducts(null, null, null, BeanType.SINGLE_ORIGIN, null, null, null, page())));
        assertEquals(evens, ids(service.getProducts(null, null, null, null, true, null, null, page())));
        assertEquals(evens, ids(service.getProducts(null, null, null, null, null, RoastLevel.MEDIUM, null, page())));
    }
    @Test void adminStatesAndFirstAdjacentLastEmptyPagesRetainCountsAndOrdering() {
        assertEquals(8, service.getAdminProducts(null, null, null, null, null, null, null, null, page()).getTotalElements());
        for (var status : ProductStatus.values()) {
            long count = jdbc.queryForObject("select count(*) from product where status=?", Long.class, status.name());
            assertEquals(count, service.getAdminProducts(null, null, null, null, null, null, status, null, page()).getTotalElements());
        }
        var collected = new ArrayList<Long>();
        for (int p = 0; p < 4; p++) {
            var result = service.getProducts(null, null, null, null, null, null, null, PageRequest.of(p, 2, Sort.by("price").descending()));
            assertEquals(6, result.getTotalElements()); assertEquals(3, result.getTotalPages()); collected.addAll(ids(result));
            assertEquals(p == 3, result.isEmpty());
        }
        assertEquals(products.subList(0, 6).reversed().stream().map(Product::getId).toList(), collected);
        assertThrows(com.back.coffeeprod.global.exception.CustomException.class, () -> service.getProduct(products.get(7).getId()));
    }
    @Test void publicHttpSearchAndAdminAuthorizationArePreserved() throws Exception {
        var client = HttpClient.newHttpClient();
        String query = URLEncoder.encode("에티오피아", java.nio.charset.StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/products?keyword=" + query + "&sort=id,asc")).timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var data = JsonMapper.builder().build().readTree(response.body()).path("data");
        assertEquals(2, data.path("totalElements").asInt()); assertEquals(products.getFirst().getId().longValue(), data.path("content").get(0).path("id").asLong());
        var admin = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/products")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, admin.statusCode());
    }
}

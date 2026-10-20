package com.back.coffeeprod.dev.order;

import com.back.coffeeprod.domain.cart.entity.GrindType;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.entity.OrderItem;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.product.repository.*;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Same read-only HTTP contracts on H2 and an explicitly owned PostgreSQL database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false", "app.payment.workers-enabled=false",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password=dev2-unused",
        "jwt.secret-key=dev2-synthetic-signing-key-at-least-32-bytes", "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000", "pg.toss.client-key=dev2-unused", "pg.toss.secret-key=dev2-unused"
})
class DevTask2OrderQueryIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        boolean postgres = System.getenv("DEV_TASK2_PG_URL") != null;
        var settings = postgres ? DevTask2PostgresProbe.settings() : null;
        properties.add("spring.datasource.url", () -> postgres ? settings.url()
                : "jdbc:h2:mem:devtask2;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        properties.add("spring.datasource.driver-class-name", () -> postgres ? "org.postgresql.Driver" : "org.h2.Driver");
        properties.add("spring.datasource.username", () -> postgres ? "devtask2" : "sa");
        properties.add("spring.datasource.password", () -> postgres ? settings.password() : "");
        properties.add("spring.jpa.properties.hibernate.dialect", () -> postgres ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> postgres ? "validate" : "create-drop");
        properties.add("spring.flyway.enabled", () -> postgres);
    }
    @Autowired MemberRepository members;
    @Autowired OrderRepository orders;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @LocalServerPort int port;
    Member owner, other, empty, admin;
    Product first, second;
    final List<Long> ownerIds = new ArrayList<>();
    final List<Long> otherIds = new ArrayList<>();
    Map<String, Object> stored;

    @BeforeAll static void runtime() {
        System.out.printf("DEV2_RUNTIME pid=%d database=%s%n", ProcessHandle.current().pid(),
                System.getenv("DEV_TASK2_PG_URL") == null ? "H2" : "PostgreSQL");
    }

    @BeforeEach void fixture() {
        // Only DEV2 synthetic rows, in the dedicated test database, survive between application starts.
        jdbc.update("delete from order_item where order_id in (select order_id from orders where toss_order_id like 'DEV2-%')");
        jdbc.update("delete from orders where toss_order_id like 'DEV2-%'");
        jdbc.update("delete from product where sku like 'DEV2-%'");
        jdbc.update("delete from category where name like 'DEV2-%'");
        jdbc.update("delete from member where email like 'dev2-%@test.invalid'");
        owner = member(Role.USER); other = member(Role.USER); empty = member(Role.USER); admin = member(Role.ADMIN);
        var category = categories.saveAndFlush(Category.builder().name(unique()).build());
        first = product(category, "DEV2 첫 원두", 9000); second = product(category, "DEV2 둘째 원두", 11000);
        ownerIds.clear(); otherIds.clear();
        for (int i = 0; i < 5; i++) ownerIds.add(order(owner, i * 2));
        for (int i = 0; i < 3; i++) otherIds.add(order(other, i * 2 + 1));
        Collections.reverse(ownerIds); Collections.reverse(otherIds);
        stored = state();
    }
    @AfterEach void readDidNotWrite() { assertEquals(stored, state(), "조회 전후 주문/상품/회원 저장값 보존"); }
    String unique() { return "DEV2-" + UUID.randomUUID(); }
    Member member(Role role) {
        String id = unique();
        return members.saveAndFlush(Member.builder().email(id.toLowerCase(Locale.ROOT) + "@test.invalid")
                .nickname(id).name("DEV2 합성 회원").password("dev2-no-password-login").role(role).build());
    }
    Product product(Category category, String name, int price) {
        return products.saveAndFlush(Product.builder().category(category).sku(unique()).name(name).price(price)
                .weightGrams(200).stockQuantity(12).roastLevel(RoastLevel.MEDIUM).description("합성")
                .imageUrl("https://assets-coffeeprod.ttagyulab.com/products/catalog/so001-l-v1.webp").build());
    }
    Long order(Member member, int hours) {
        var order = Orders.builder().member(member).tossOrderId(unique()).productTotalPrice(17000).deliveryFee(3000)
                .usedMileage(300).totalPrice(19700).deliveryAddress("[12345] DEV2 합성 배송지").build();
        order.getOrderItems().add(OrderItem.builder().orders(order).product(first).orderPrice(5000).quantity(2).grindType(GrindType.DRIP).build());
        order.getOrderItems().add(OrderItem.builder().orders(order).product(second).orderPrice(7000).quantity(1).grindType(GrindType.WHOLE_BEAN).build());
        Long id = orders.saveAndFlush(order).getId();
        jdbc.update("update orders set order_date=? where order_id=?", Timestamp.from(BASE.plus(Duration.ofHours(hours))), id);
        return id;
    }
    Map<String,Object> state() {
        var result = new LinkedHashMap<String,Object>();
        for (String table : List.of("member", "orders", "order_item", "product", "category"))
            result.put(table, jdbc.queryForList("select * from " + table + " order by 1"));
        return result;
    }
    JsonNode get(String path, Member actor, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).GET();
        if (actor != null) request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor.getId(), actor.getRole()));
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), "HTTP 상태 (인증 원문 생략)");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        var result = JSON.readTree(response.body());
        assertEquals(status, result.path("status").asInt());
        assertTrue(result.path("message").isTextual()); assertTrue(result.has("data")); assertTrue(result.has("errors"));
        if (status != 200) assertTrue(result.path("data").isNull());
        return result.path("data");
    }
    List<Long> ids(JsonNode page) { return page.path("content").valueStream().map(row -> row.path("orderId").asLong()).toList(); }
    void summary(JsonNode row) {
        assertEquals("PENDING", row.path("status").asText()); assertEquals(19700, row.path("totalPrice").asInt());
        assertEquals(2, row.path("itemCount").asInt()); assertFalse(row.path("cancelRequested").asBoolean());
        assertTrue(row.path("paymentOperationStatus").isNull());
        assertTrue(Set.of(first.getName(), second.getName()).contains(row.path("firstProductName").asText()));
    }

    @Test @DisplayName("회원 주문 목록은 주문당 한 행과 올바른 상품 요약을 반환한다")
    void memberList() throws Exception {
        var page = get("/api/v1/orders?size=20", owner, 200);
        assertEquals(5, page.path("totalElements").asInt()); assertEquals(new HashSet<>(ownerIds), new HashSet<>(ids(page)));
        assertEquals(5, ids(page).size()); page.path("content").forEach(this::summary);
    }
    @Test @DisplayName("회원별 목록은 다른 회원 주문을 섞지 않는다")
    void memberIsolation() throws Exception { assertEquals(otherIds, ids(get("/api/v1/orders?size=20", other, 200))); }
    @Test @DisplayName("회원 목록은 주문 시각 내림차순이며 응답 시각은 KST다")
    void newestFirst() throws Exception {
        var page = get("/api/v1/orders?size=20", owner, 200); assertEquals(ownerIds, ids(page));
        var time = OffsetDateTime.parse(page.path("content").get(0).path("orderDate").asText());
        assertEquals(BASE.plus(Duration.ofHours(8)), time.toInstant()); assertEquals(ZoneOffset.ofHours(9), time.getOffset());
    }
    @Test @DisplayName("인접 페이지는 중복이 없고 전체 건수와 마지막 페이지가 일치한다")
    void adjacentPages() throws Exception {
        var combined = new ArrayList<Long>();
        for (int i = 0; i < 3; i++) {
            var page = get("/api/v1/orders?size=2&page=" + i, owner, 200);
            assertEquals(5, page.path("totalElements").asInt()); assertEquals(3, page.path("totalPages").asInt());
            assertEquals(i == 2 ? 1 : 2, ids(page).size()); combined.addAll(ids(page));
        }
        assertEquals(ownerIds, combined); assertEquals(5, new HashSet<>(combined).size());
    }
    @Test @DisplayName("주문 없는 회원과 범위 초과 페이지는 빈 목록을 반환한다")
    void emptyPages() throws Exception {
        var noOrders = get("/api/v1/orders", empty, 200); assertTrue(ids(noOrders).isEmpty()); assertEquals(0, noOrders.path("totalElements").asInt());
        assertTrue(ids(get("/api/v1/orders?size=2&page=5", owner, 200)).isEmpty());
    }
    @Test @DisplayName("주문 상세는 모든 상품과 주문 가격 스냅샷·옵션·금액을 반환한다")
    void detailSnapshot() throws Exception {
        var detail = get("/api/v1/orders/" + ownerIds.getFirst(), owner, 200);
        assertEquals(ownerIds.getFirst().longValue(), detail.path("orderId").asLong()); assertEquals(17000, detail.path("productTotalPrice").asInt());
        assertEquals(3000, detail.path("deliveryFee").asInt()); assertEquals(300, detail.path("usedMileage").asInt());
        assertEquals(19700, detail.path("totalPrice").asInt()); assertEquals("[12345] DEV2 합성 배송지", detail.path("deliveryAddress").asText());
        assertEquals(2, detail.path("orderItems").size());
        assertEquals(Set.of(first.getId(), second.getId()), new HashSet<>(detail.path("orderItems").valueStream()
                .map(item -> item.path("productId").asLong()).toList()));
        for (var item : detail.path("orderItems")) {
            boolean isFirst = item.path("productId").asLong() == first.getId();
            assertEquals(isFirst ? first.getId().longValue() : second.getId().longValue(), item.path("productId").asLong());
            assertEquals(isFirst ? first.getName() : second.getName(), item.path("productName").asText());
            assertEquals(isFirst ? 5000 : 7000, item.path("orderPrice").asInt()); assertEquals(isFirst ? 2 : 1, item.path("quantity").asInt());
            assertEquals(isFirst ? "DRIP" : "WHOLE_BEAN", item.path("grindType").asText()); assertEquals(isFirst ? 10000 : 7000, item.path("subTotal").asInt());
        }
    }
    @Test @DisplayName("타인 주문은403이고 없는 주문은404다")
    void inaccessibleDetails() throws Exception { get("/api/v1/orders/" + ownerIds.getFirst(), other, 403); get("/api/v1/orders/9223372036854775807", owner, 404); }
    @Test @DisplayName("비인증 회원 주문 목록과 상세는401이다")
    void anonymousMemberEndpoints() throws Exception { get("/api/v1/orders", null, 401); get("/api/v1/orders/" + ownerIds.getFirst(), null, 401); }
    @Test @DisplayName("관리자 목록은 전체 회원 주문과 회원 정보·페이징을 반환한다")
    void adminList() throws Exception {
        var combined = new ArrayList<Long>();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            var page = get("/api/v1/admin/orders?size=3&page=" + pageNumber, admin, 200); assertEquals(8, page.path("totalElements").asInt());
            for (var row : page.path("content")) {
                summary(row); boolean mine = ownerIds.contains(row.path("orderId").asLong());
                assertEquals(mine ? owner.getId().longValue() : other.getId().longValue(), row.path("memberId").asLong());
                assertEquals(mine ? owner.getEmail() : other.getEmail(), row.path("memberEmail").asText());
            }
            combined.addAll(ids(page));
        }
        var expected = new ArrayList<>(ownerIds); expected.addAll(otherIds);
        assertEquals(new HashSet<>(expected), new HashSet<>(combined)); assertEquals(8, combined.size());
        assertEquals(List.of(ownerIds.get(0), ownerIds.get(1), otherIds.get(0), ownerIds.get(2),
                otherIds.get(1), ownerIds.get(3), otherIds.get(2), ownerIds.get(4)), combined);
    }
    @Test @DisplayName("일반회원과 비인증 사용자의 관리자 주문 목록은403과401이다")
    void adminAuthorization() throws Exception { get("/api/v1/admin/orders", owner, 403); get("/api/v1/admin/orders", null, 401); }
}

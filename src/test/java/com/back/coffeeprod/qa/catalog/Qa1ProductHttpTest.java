package com.back.coffeeprod.qa.catalog;

import com.back.coffeeprod.domain.cart.entity.GrindType;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.entity.OrderItem;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.product.dto.ProductDto;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.product.repository.*;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Dedicated H2 and real HTTP/security/services. No external Redis or PG calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:qa1;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false", "spring.flyway.enabled=false",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password=qa1-unused",
        "jwt.secret-key=qa1-synthetic-signing-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=qa1-unused", "pg.toss.secret-key=qa1-unused", "app.payment.workers-enabled=false"
})
class Qa1ProductHttpTest {
    static final String IMAGE = "https://assets-coffeeprod.ttagyulab.com/products/catalog/so001-l-v1.webp";
    static final String UPDATED = "https://assets-coffeeprod.ttagyulab.com/products/catalog/so001-l-v2.webp";
    static final String ADMIN = "/api/v1/admin/products";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Autowired MemberRepository members;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @LocalServerPort int port;
    Member admin, owner, other;
    Category category;

    @BeforeEach void fixture() {
        admin = member(Role.ADMIN); owner = member(Role.USER); other = member(Role.USER);
        category = categories.saveAndFlush(Category.builder().name(unique()).build());
    }
    String unique() { return "QA1-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT); }
    Member member(Role role) {
        String id = unique();
        return members.saveAndFlush(Member.builder().email(id + "@test.invalid").nickname(id).name("QA1 합성 회원")
                .password("qa1-no-password-login").role(role).build());
    }
    String token(Member member) { return member == null ? null : jwt.generateAccessToken(member.getId(), member.getRole()); }
    <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(manager).execute(s -> { em.clear(); T result = action.get(); em.flush(); return result; });
    }
    Product product(ProductStatus status) {
        return tx(() -> {
            var p = Product.builder().category(category).sku(unique()).weightGrams(200).name("QA1 원본 원두")
                    .price(10000).stockQuantity(12).roastLevel(RoastLevel.MEDIUM).description("원본 설명").imageUrl(IMAGE).build();
            p.updateStatus(status); return products.saveAndFlush(p);
        });
    }
    Map<String,Object> input(String sku, String image) {
        var body = new LinkedHashMap<String,Object>();
        body.put("categoryId", category.getId()); body.put("sku", sku); body.put("weightGrams", 500);
        body.put("name", "QA1 변경 원두"); body.put("price", 23000); body.put("stockQuantity", 7);
        body.put("roastLevel", "DARK"); body.put("description", "변경 설명"); body.put("image_url", image);
        return body;
    }
    HttpResponse<String> http(String method, String route, Object body, Member actor) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (actor != null) builder.header("Authorization", "Bearer " + token(actor));
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response, int expected) {
        assertEquals(expected, response.statusCode(), "HTTP 상태 (인증 원문 생략)");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        var root = JSON.readTree(response.body());
        assertEquals(expected == 201 ? 200 : expected, root.path("status").asInt());
        assertTrue(root.path("message").isTextual()); assertTrue(root.has("data")); assertTrue(root.has("errors"));
        if (expected < 400) assertTrue(root.path("errors").isNull());
        else assertTrue(root.path("data").isNull());
        return root.path("data");
    }
    JsonNode stored(Long id) {
        return tx(() -> JSON.readTree(JSON.writeValueAsString(new ProductDto.DetailResponse(products.findById(id).orElseThrow()))));
    }
    List<Object> state() {
        return tx(() -> {
            var state = new ArrayList<Object>();
            for (String table : List.of("product", "category", "orders", "order_item", "member", "payment", "payment_operation"))
                state.add(jdbc.queryForList("select * from " + table + " order by 1"));
            return state;
        });
    }
    void unchanged(List<Object> before) { assertTrue(before.equals(state()), "모든 상품·주문·회원·결제 값과 행 수 보존"); }
    void assertSaved(JsonNode response, Long id, String image, int stock) {
        var persisted = stored(id);
        assertEquals(persisted, response, "응답 전체 업무 필드와 새 트랜잭션 Repository 조회 일치");
        if (image == null) assertTrue(persisted.path("imageUrl").isNull());
        else assertEquals(image, persisted.path("imageUrl").asText());
        assertEquals("QA1 변경 원두", persisted.path("name").asText());
        assertEquals(23000, persisted.path("price").asInt()); assertEquals(500, persisted.path("weightGrams").asInt());
        assertEquals("DARK", persisted.path("roastLevel").asText()); assertEquals("변경 설명", persisted.path("description").asText());
        assertEquals(stock, persisted.path("stockQuantity").asInt());
    }
    static Stream<Arguments> acceptedImages() {
        return Stream.of("create", "update").flatMap(mode -> Arrays.asList(UPDATED, null, "", " \t ").stream()
                .map(image -> Arguments.of(mode, image)));
    }
    @ParameterizedTest(name = "QA1-IMG-OK {0} / {index}") @MethodSource("acceptedImages")
    @DisplayName("상품 등록·수정 HTTP는 승인 URL을 저장하고 null·빈 문자열·공백을 정규화한다")
    void acceptedImages(String mode, String image) throws Exception {
        var p = product(ProductStatus.ON_SALE); long before = products.count();
        boolean create = mode.equals("create");
        var response = data(http(create ? "POST" : "PUT", ADMIN + (create ? "" : "/" + p.getId()),
                input(create ? unique() : p.getSku(), image), admin), create ? 201 : 200);
        Long id = response.path("id").asLong();
        assertSaved(response, id, image == null || image.isBlank() ? null : image, create ? 7 : 12);
        assertEquals(before + (create ? 1 : 0), products.count());
        assertEquals(response, data(http("GET", ADMIN + "/" + id, null, admin), 200));
    }
    static Stream<Arguments> rejectedImages() {
        return Stream.of("create", "update").flatMap(mode -> Stream.of("http://assets-coffeeprod.ttagyulab.com/products/catalog/so001-l-v1.webp",
                "https://example.com/products/catalog/so001-l-v1.webp", "/products/catalog/so001-l-v1.webp")
                .map(image -> Arguments.of(mode, image)));
    }
    @ParameterizedTest(name = "QA1-IMG-NG {0} / {index}") @MethodSource("rejectedImages")
    @DisplayName("상품 등록·수정 HTTP는 미승인 URL을400으로 거부하고 모든 저장값을 보존한다")
    void rejectedImages(String mode, String image) throws Exception {
        var p = product(ProductStatus.ON_SALE); var before = state(); boolean create = mode.equals("create");
        var response = http(create ? "POST" : "PUT", ADMIN + (create ? "" : "/" + p.getId()),
                input(create ? unique() : p.getSku(), image), admin);
        data(response, 400);
        assertEquals("상품 이미지 URL이 허용된 형식이 아닙니다.", JSON.readTree(response.body()).path("message").asText());
        unchanged(before); assertEquals(IMAGE, stored(p.getId()).path("imageUrl").asText());
    }
    @Test @DisplayName("QA1-LIST 공개 목록은 판매중만 관리자 목록은 전체 상태를 페이지별 반환한다")
    void lists() throws Exception {
        var sale = product(ProductStatus.ON_SALE); var sold = product(ProductStatus.SOLD_OUT); var hidden = product(ProductStatus.HIDDEN);
        var before = state(); String query = "?categoryId=" + category.getId() + "&size=1&sort=id,asc&page=";
        var publicPage = data(http("GET", "/api/v1/products" + query + "0", null, null), 200);
        assertEquals(1, publicPage.path("totalElements").asInt()); assertEquals(1, publicPage.path("content").size());
        assertEquals(sale.getId().longValue(), publicPage.path("content").get(0).path("id").asLong());
        assertEquals(IMAGE, publicPage.path("content").get(0).path("imageUrl").asText());
        assertEquals(0, data(http("GET", "/api/v1/products" + query + "1", null, null), 200).path("content").size());
        var expected = List.of(sale, sold, hidden);
        for (int i = 0; i < 3; i++) {
            var page = data(http("GET", ADMIN + query + i, null, admin), 200);
            assertEquals(3, page.path("totalElements").asInt()); assertEquals(1, page.path("content").size());
            var item = page.path("content").get(0);
            assertEquals(expected.get(i).getId().longValue(), item.path("id").asLong());
            assertEquals(expected.get(i).getStatus().name(), item.path("status").asText()); assertEquals(IMAGE, item.path("imageUrl").asText());
        }
        unchanged(before);
    }
    static Stream<Arguments> details() {
        return Arrays.stream(ProductStatus.values()).flatMap(status -> Stream.of(false, true).map(admin -> Arguments.of(status, admin)));
    }
    @ParameterizedTest(name = "QA1-DETAIL {0} / admin={1}") @MethodSource("details")
    @DisplayName("공개 상세는 숨김404이고 관리자 상세는 모든 상태의 저장값을 반환한다")
    void details(ProductStatus status, boolean administrator) throws Exception {
        var p = product(status); var before = state(); int code = !administrator && status == ProductStatus.HIDDEN ? 404 : 200;
        var value = data(http("GET", (administrator ? ADMIN : "/api/v1/products") + "/" + p.getId(), null,
                administrator ? admin : null), code);
        if (code == 200) assertEquals(stored(p.getId()), value);
        unchanged(before);
    }
    static Stream<Arguments> denied() {
        return Stream.of("list", "detail", "create", "update").flatMap(op -> Stream.of("anonymous", "user").map(actor -> Arguments.of(op, actor)));
    }
    @ParameterizedTest(name = "QA1-AUTH {0} / {1}") @MethodSource("denied")
    @DisplayName("관리자 상품 조회·등록·수정은 비인증401·일반회원403이며 자원을 보존한다")
    void denied(String op, String actor) throws Exception {
        var p = product(ProductStatus.ON_SALE); var before = state();
        String method = op.equals("create") ? "POST" : op.equals("update") ? "PUT" : "GET";
        String path = ADMIN + (op.equals("detail") || op.equals("update") ? "/" + p.getId() : "");
        data(http(method, path, method.equals("GET") ? null : input(unique(), UPDATED), actor.equals("user") ? owner : null),
                actor.equals("user") ? 403 : 401); unchanged(before);
    }
    Orders order(Product p, Member member) {
        return tx(() -> {
            var order = Orders.builder().member(member).tossOrderId(unique()).productTotalPrice(17000).deliveryFee(3000)
                    .totalPrice(20000).usedMileage(0).deliveryAddress("QA1 합성 주소").build();
            order.getOrderItems().add(OrderItem.builder().orders(order).product(p).orderPrice(8500).quantity(2).grindType(GrindType.DRIP).build());
            return orders.saveAndFlush(order);
        });
    }
    @Test @DisplayName("QA1-ORDER 본인 목록·상세는 주문 가격·상품명·수량·분쇄 옵션과 회원 격리를 유지한다")
    void ownOrder() throws Exception {
        var p = product(ProductStatus.ON_SALE); var order = order(p, owner); var others = order(p, other); var before = state();
        var list = data(http("GET", "/api/v1/orders?size=20&sort=id,asc", null, owner), 200);
        assertEquals(1, list.path("totalElements").asInt()); assertEquals(1, list.path("content").size());
        var first = list.path("content").get(0);
        assertEquals(order.getId().longValue(), first.path("orderId").asLong()); assertEquals(20000, first.path("totalPrice").asInt());
        assertEquals(1, first.path("itemCount").asInt()); assertEquals("QA1 원본 원두", first.path("firstProductName").asText());
        var detail = data(http("GET", "/api/v1/orders/" + order.getId(), null, owner), 200);
        assertEquals(order.getId().longValue(), detail.path("orderId").asLong()); assertEquals(20000, detail.path("totalPrice").asInt());
        assertEquals(17000, detail.path("productTotalPrice").asInt()); assertEquals(1, detail.path("orderItems").size());
        var item = detail.path("orderItems").get(0);
        assertEquals(p.getId().longValue(), item.path("productId").asLong()); assertEquals("QA1 원본 원두", item.path("productName").asText());
        assertEquals(8500, item.path("orderPrice").asInt()); assertEquals(2, item.path("quantity").asInt());
        assertEquals(17000, item.path("subTotal").asInt()); assertEquals("DRIP", item.path("grindType").asText());
        assertEquals(others.getId().longValue(), data(http("GET", "/api/v1/orders", null, other), 200).path("content").get(0).path("orderId").asLong());
        var saved = tx(() -> {
            var row = orders.findById(order.getId()).orElseThrow(); var savedItem = row.getOrderItems().get(0);
            return List.of(row.getTotalPrice(), savedItem.getOrderPrice(), savedItem.getQuantity(), savedItem.getGrindType());
        });
        assertEquals(List.of(20000, 8500, 2, GrindType.DRIP), saved); unchanged(before);
    }
    @ParameterizedTest(name = "QA1-ORDER-AUTH {0}") @ValueSource(strings = {"anonymous", "other"})
    @DisplayName("주문 목록·상세는 비인증401·타인상세403을 반환하고 재고·마일리지를 보존한다")
    void deniedOrder(String actor) throws Exception {
        var p = product(ProductStatus.ON_SALE); var order = order(p, owner); var before = state(); boolean anonymous = actor.equals("anonymous");
        data(http("GET", "/api/v1/orders/" + order.getId(), null, anonymous ? null : other), anonymous ? 401 : 403);
        if (anonymous) data(http("GET", "/api/v1/orders", null, null), 401);
        else assertEquals(0, data(http("GET", "/api/v1/orders", null, other), 200).path("totalElements").asInt());
        unchanged(before);
    }
}

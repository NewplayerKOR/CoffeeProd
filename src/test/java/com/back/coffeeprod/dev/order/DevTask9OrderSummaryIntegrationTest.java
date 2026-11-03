package com.back.coffeeprod.dev.order;

import com.back.coffeeprod.domain.cart.entity.GrindType;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.order.entity.OrderItem;
import com.back.coffeeprod.domain.order.entity.OrderStatus;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.payment.entity.PaymentOperation;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Same fixtures/public service measurements before and after DEV9-5; no external services. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "app.payment.workers-enabled=false",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password=dev9-unused",
        "jwt.secret-key=dev9-order-summary-synthetic-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=dev9-unused", "pg.toss.secret-key=dev9-unused"
})
class DevTask9OrderSummaryIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant BASE = Instant.parse("2026-01-01T15:01:02.123456Z");
    static boolean postgres() { return System.getenv("DEV_TASK9_5_PG_URL") != null; }
    // Only the pre-change measurement run omits assertions about the not-yet-implemented structure.
    static boolean baseline() { return "true".equals(System.getenv("DEV_TASK9_5_BASELINE")); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("DEV_TASK9_5_PG_URL");
        if (postgres()) {
            var match = java.util.regex.Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:(\\d+)/devtask9_5_[a-z0-9_]+")
                    .matcher(url);
            if (!match.matches() || Integer.parseInt(match.group(1)) <= 1024
                    || Integer.parseInt(match.group(1)) > 65535 || Integer.parseInt(match.group(1)) == 5432
                    || System.getenv("DEV_TASK9_5_PG_PASSWORD") == null)
                throw new IllegalArgumentException("Dedicated localhost non-5432 DEV9-5 PostgreSQL required");
            UUID.fromString(System.getenv("DEV_TASK9_5_OWNER"));
        }
        registry.add("spring.datasource.url", () -> postgres() ? url
                : "jdbc:h2:mem:devtask9_5;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        registry.add("spring.datasource.driver-class-name", () -> postgres() ? "org.postgresql.Driver" : "org.h2.Driver");
        registry.add("spring.datasource.username", () -> postgres() ? "devtask9" : "sa");
        registry.add("spring.datasource.password", () -> postgres() ? System.getenv("DEV_TASK9_5_PG_PASSWORD") : "");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> postgres() ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> postgres() ? "validate" : "create-drop");
        registry.add("spring.flyway.enabled", () -> postgres());
    }

    @Autowired OrderService service;
    @Autowired OrderRepository repository;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @LocalServerPort int port;
    Member owner, other, empty, admin;
    Product first, second, third;
    final List<Long> ownerIds = new ArrayList<>();
    final List<Long> otherIds = new ArrayList<>();

    <T> T tx(Supplier<T> work) { return new TransactionTemplate(manager).execute(s -> work.get()); }

    @BeforeEach void reset() {
        for (String table : List.of("payment_operation", "sales_rebuild_request", "payment", "order_item", "orders",
                "cart_item", "cart", "address", "product", "category", "member")) jdbc.update("delete from " + table);
        ownerIds.clear(); otherIds.clear();
        tx(() -> {
            owner = member(Role.USER); other = member(Role.USER); empty = member(Role.USER); admin = member(Role.ADMIN);
            var category = Category.builder().name("요약 검증 " + UUID.randomUUID()).build(); em.persist(category);
            first = product(category, "최소 상품 ID", 1000);
            second = product(category, "최소 주문상품 ID 대표 원두", 2000);
            third = product(category, "마지막 원두", 3000);
            return null;
        });
    }

    Member member(Role role) {
        String id = UUID.randomUUID().toString();
        var result = Member.builder().email(id + "@test.invalid").nickname(id).name("합성 회원")
                .password("unused").role(role).build(); em.persist(result); return result;
    }
    Product product(Category category, String name, int price) {
        var result = Product.builder().category(category).sku("D95-" + UUID.randomUUID()).name(name).price(price)
                .weightGrams(200).stockQuantity(50).roastLevel(RoastLevel.MEDIUM).description("합성 설명")
                .imageUrl("https://assets-coffeeprod.ttagyulab.com/products/catalog/so001-l-v1.webp").build();
        em.persist(result); return result;
    }
    Long order(Member member, int sequence, int lines) {
        var order = Orders.builder().member(em.getReference(Member.class, member.getId())).tossOrderId("D95-" + UUID.randomUUID())
                .productTotalPrice(20000).deliveryFee(3000).totalPrice(22700).usedMileage(300)
                .deliveryAddress("합성 배송 스냅샷").build();
        // The minimum item ID deliberately points at a product whose ID is not the minimum.
        for (int i = 0; i < lines; i++) {
            Product product = i == 0 || i == 1 ? second : i % 2 == 0 ? first : third;
            order.getOrderItems().add(OrderItem.builder().orders(order)
                    .product(em.getReference(Product.class, product.getId())).orderPrice(500 + i)
                    .quantity(i + 2).grindType(i == 0 ? GrindType.WHOLE_BEAN : GrindType.DRIP).build());
        }
        em.persist(order); em.flush();
        jdbc.update("update orders set order_date=?, tracking_no=? where order_id=?",
                Timestamp.from(BASE.plusSeconds(sequence)), "TRACK-" + sequence, order.getId());
        return order.getId();
    }
    void fixture(int own, int others, int lines) {
        tx(() -> {
            for (int i = 0; i < own; i++) ownerIds.add(order(owner, i * 2, lines));
            for (int i = 0; i < others; i++) otherIds.add(order(other, i * 2 + 1, lines));
            return null;
        });
        Collections.reverse(ownerIds); Collections.reverse(otherIds);
    }
    void operation(Long orderId, PaymentOperation.Type type, PaymentOperation.Status state) {
        var order = em.find(Orders.class, orderId);
        var operation = new PaymentOperation(order, type, "synthetic-" + UUID.randomUUID(), "USER", owner.getId());
        switch (state) {
            case READY -> { }
            case IN_FLIGHT -> operation.claim(Instant.now(), Duration.ofSeconds(120), false);
            case SUCCEEDED -> operation.complete(BASE, type == PaymentOperation.Type.CANCEL_FULL ? BASE : null, "synthetic");
            case REJECTED -> operation.reject();
            case UNKNOWN -> operation.unknown("SYNTHETIC");
            case MANUAL_REQUIRED -> operation.manual("SYNTHETIC");
        }
        em.persist(operation);
    }
    Map<String, Object> state() {
        var result = new LinkedHashMap<String, Object>();
        for (String table : List.of("member", "orders", "order_item", "product", "payment_operation"))
            result.put(table, jdbc.queryForList("select * from " + table + " order by 1"));
        return result;
    }
    void assertSummary(JsonNode row) {
        long id = row.path("orderId").asLong();
        assertEquals(jdbc.queryForObject("select count(*) from order_item where order_id=?", Integer.class, id), row.path("itemCount").asInt());
        assertEquals(jdbc.queryForObject("select p.name from order_item oi join product p on p.product_id=oi.product_id where oi.order_id=? order by oi.order_item_id limit 1", String.class, id), row.path("firstProductName").asText());
        assertEquals(22700, row.path("totalPrice").asInt());
        var date = OffsetDateTime.parse(row.path("orderDate").asText());
        assertEquals(ZoneOffset.ofHours(9), date.getOffset());
        assertEquals(jdbc.queryForObject("select order_date from orders where order_id=?", OffsetDateTime.class, id).toInstant(), date.toInstant());
    }

    @ParameterizedTest @CsvSource({"10,false", "50,false", "10,true", "50,true"})
    void pageLoading(int size, boolean administrator) {
        fixture(63, 7, 20);
        tx(() -> { for (Long id : ownerIds) operation(id, PaymentOperation.Type.CONFIRM, PaymentOperation.Status.UNKNOWN); return null; });
        var page = PageRequest.of(0, size);
        // Warm the same public service, then measure with a new persistence context.
        if (administrator) service.getAllOrders(page); else service.getMyOrders(owner.getId(), page);
        var statistics = emf.unwrap(SessionFactory.class).getStatistics(); statistics.clear();
        long started = System.nanoTime();
        Page<?> response = administrator ? service.getAllOrders(page) : service.getMyOrders(owner.getId(), page);
        long nanos = System.nanoTime() - started;
        long sql = statistics.getPrepareStatementCount(), entities = statistics.getEntityLoadCount(), collections = statistics.getCollectionLoadCount();
        System.out.printf("DEV9_ORDER_METRICS database=%s baseline=%s admin=%s size=%d sql=%d entities=%d collections=%d nanos=%d%n",
                postgres() ? "PostgreSQL" : "H2", baseline(), administrator, size, sql, entities, collections, nanos);
        assertEquals(size, response.getNumberOfElements()); assertEquals(administrator ? 70 : 63, response.getTotalElements());
        var expected = administrator ? repository.findAllIds(page) : repository.findIdsByMemberId(owner.getId(), page);
        var rows = JSON.valueToTree(response.getContent());
        assertEquals(expected.getContent(), rows.valueStream().map(r -> r.path("orderId").asLong()).toList());
        rows.forEach(this::assertSummary);
        if (!baseline()) {
            assertEquals(3, sql, "ID page + count + summary, without payment-operation lazy queries");
            assertEquals(0, entities); assertEquals(0, collections);
        }
    }

    @Test void lineCountAndMinimumItemRepresentative() {
        fixture(1, 0, 4);
        var row = service.getMyOrders(owner.getId(), PageRequest.of(0, 10)).getContent().getFirst();
        assertEquals(4, row.getItemCount()); assertEquals(second.getName(), row.getFirstProductName());
        assertNotEquals(first.getName(), row.getFirstProductName());
        assertEquals(3, jdbc.queryForObject("select count(distinct product_id) from order_item", Integer.class));
        assertEquals(14, jdbc.queryForObject("select sum(quantity) from order_item", Integer.class));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void paymentStatusMatrix(boolean cancelRequested) {
        var expected = new HashMap<Long, String>();
        var states = new ArrayList<PaymentOperation.Status>(); states.add(null); states.addAll(List.of(PaymentOperation.Status.values()));
        tx(() -> {
            int sequence = 0;
            for (var confirm : states) for (var cancel : states) {
                Long id = order(owner, sequence++, 2);
                if (confirm != null) operation(id, PaymentOperation.Type.CONFIRM, confirm);
                if (cancel != null) operation(id, PaymentOperation.Type.CANCEL_FULL, cancel);
                if (cancelRequested) em.find(Orders.class, id).requestCancel();
                var value = cancelRequested && cancel != null ? cancel : confirm;
                expected.put(id, value == null ? null : value.name());
            }
            return null;
        });
        for (Page<?> result : List.of(service.getMyOrders(owner.getId(), PageRequest.of(0, 100)), service.getAllOrders(PageRequest.of(0, 100)))) {
            assertEquals(49, result.getNumberOfElements());
            for (var row : JSON.valueToTree(result.getContent())) {
                assertEquals(cancelRequested, row.path("cancelRequested").asBoolean());
                String actual = row.path("paymentOperationStatus").isNull() ? null : row.path("paymentOperationStatus").asText();
                assertEquals(expected.get(row.path("orderId").asLong()), actual);
            }
        }
    }

    @Test void adjacentAndLastPages() {
        fixture(23, 7, 2);
        var combined = new ArrayList<Long>();
        for (int page = 0; page < 3; page++) {
            var result = service.getMyOrders(owner.getId(), PageRequest.of(page, 10));
            assertEquals(23, result.getTotalElements()); assertEquals(3, result.getTotalPages());
            assertEquals(page == 2 ? 3 : 10, result.getNumberOfElements());
            result.forEach(r -> combined.add(r.getOrderId()));
        }
        assertEquals(ownerIds, combined); assertEquals(23, new HashSet<>(combined).size());
        assertEquals(30, service.getAllOrders(PageRequest.of(0, 50)).getTotalElements());
    }

    @Test void emptyPagesRetainExistingMetadata() {
        fixture(2, 1, 2);
        var request = PageRequest.of(5, 10);
        for (var result : List.of(service.getMyOrders(empty.getId(), request), service.getMyOrders(owner.getId(), request), service.getAllOrders(request))) {
            assertTrue(result.isEmpty()); assertEquals(0, result.getTotalElements()); assertEquals(request, result.getPageable());
        }
        assertTrue(service.getMyOrders(Long.MAX_VALUE, PageRequest.of(0, 10)).isEmpty());
    }

    @Test void detailSnapshotsAndStoredValuesArePreserved() {
        fixture(2, 1, 4);
        var before = state();
        var detail = service.getOrder(owner.getId(), ownerIds.getFirst());
        // Detail has no item ORDER BY contract. Compare snapshots by item identity in this test only.
        var byItemId = Comparator.comparing(com.back.coffeeprod.domain.order.dto.OrderDto.OrderItemResponse::getOrderItemId);
        detail.getOrderItems().sort(byItemId);
        var beforeDetail = JSON.valueToTree(detail);
        service.getMyOrders(owner.getId(), PageRequest.of(0, 10)); service.getAllOrders(PageRequest.of(0, 50));
        var afterDetail = service.getOrder(owner.getId(), ownerIds.getFirst());
        afterDetail.getOrderItems().sort(byItemId);
        assertEquals(beforeDetail, JSON.valueToTree(afterDetail));
        assertEquals(4, detail.getOrderItems().size()); assertEquals(500, detail.getOrderItems().getFirst().getOrderPrice());
        assertEquals(List.of(500, 501, 502, 503), detail.getOrderItems().stream().map(item -> item.getOrderPrice()).toList());
        assertEquals(List.of(2, 3, 4, 5), detail.getOrderItems().stream().map(item -> item.getQuantity()).toList());
        assertEquals(List.of(second.getId(), second.getId(), first.getId(), third.getId()), detail.getOrderItems().stream().map(item -> item.getProductId()).toList());
        assertEquals("합성 배송 스냅샷", detail.getDeliveryAddress()); assertEquals(before, state());
    }

    @Test void representativeUsesCurrentNameRegardlessOfProductStatus() {
        fixture(1, 0, 2);
        jdbc.update("update product set name=?,status='HIDDEN' where product_id=?", "현재 숨김 원두", second.getId());
        assertEquals("현재 숨김 원두", service.getMyOrders(owner.getId(), PageRequest.of(0, 10)).getContent().getFirst().getFirstProductName());
        jdbc.update("update product set status='SOLD_OUT' where product_id=?", second.getId());
        assertEquals("현재 숨김 원두", service.getAllOrders(PageRequest.of(0, 10)).getContent().getFirst().getFirstProductName());
    }

    @Test void summaryProjectionRechecksMemberOwnership() {
        fixture(2, 1, 2);
        var ids = new ArrayList<>(ownerIds); ids.addAll(otherIds); ids.add(Long.MAX_VALUE);
        var mine = repository.findSummaryRowsByIdIn(ids, owner.getId());
        assertEquals(new HashSet<>(ownerIds), new HashSet<>(mine.stream().map(r -> r.orderId()).toList()));
        assertTrue(mine.stream().allMatch(r -> r.memberId().equals(owner.getId())));
        assertTrue(repository.findSummaryRowsByIdIn(ids, empty.getId()).isEmpty());
        assertEquals(3, repository.findSummaryRowsByIdIn(ids, null).size());
    }

    @Test void idPageOrderIsRetainedForEqualDatesAndSecondarySort() {
        fixture(23, 7, 2);
        jdbc.update("update orders set order_date=?", Timestamp.from(BASE));
        for (var direction : Sort.Direction.values()) {
            var page = PageRequest.of(1, 10, Sort.by(Sort.Order.desc("orderDate"), new Sort.Order(direction, "id")));
            assertEquals(repository.findIdsByMemberId(owner.getId(), page).getContent(),
                    service.getMyOrders(owner.getId(), page).stream().map(r -> r.getOrderId()).toList());
            assertEquals(repository.findAllIds(page).getContent(),
                    service.getAllOrders(page).stream().map(r -> r.getOrderId()).toList());
        }
    }

    @Test void everyOrderStatusIsPreserved() {
        fixture(OrderStatus.values().length, 0, 2);
        var expected = new HashMap<Long, OrderStatus>(); int index = 0;
        for (var status : OrderStatus.values()) {
            Long id = ownerIds.get(index++); expected.put(id, status);
            jdbc.update("update orders set status=? where order_id=?", status.name(), id);
        }
        service.getMyOrders(owner.getId(), PageRequest.of(0, 50)).forEach(r -> assertEquals(expected.get(r.getOrderId()), r.getStatus()));
        service.getAllOrders(PageRequest.of(0, 50)).forEach(r -> assertEquals(expected.get(r.getOrderId()), r.getStatus()));
    }

    @Test void emptyIdPageSkipsSummaryQuery() {
        fixture(2, 1, 2);
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertTrue(service.getMyOrders(empty.getId(), PageRequest.of(0, 10)).isEmpty());
        assertEquals(1, statistics.getPrepareStatementCount());
        statistics.clear();
        assertTrue(service.getAllOrders(PageRequest.of(5, 10)).isEmpty());
        assertEquals(2, statistics.getPrepareStatementCount()); assertEquals(0, statistics.getEntityLoadCount());
    }

    JsonNode get(String path, Member actor, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).GET();
        if (actor != null) request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor.getId(), actor.getRole()));
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode()); return JSON.readTree(response.body()).path("data");
    }

    @Test void httpAuthorizationAndWireFields() throws Exception {
        fixture(2, 1, 2);
        get("/api/v1/orders", null, 401); get("/api/v1/admin/orders", null, 401); get("/api/v1/admin/orders", owner, 403);
        var mine = get("/api/v1/orders?size=10", owner, 200);
        assertEquals(2, mine.path("totalElements").asInt());
        var myRow = mine.path("content").get(0);
        assertEquals(Set.of("orderId", "status", "paymentOperationStatus", "cancelRequested", "totalPrice", "orderDate", "itemCount", "firstProductName"), myRow.propertyNames());
        mine.path("content").forEach(this::assertSummary);
        var theirs = get("/api/v1/orders", other, 200);
        assertEquals(otherIds.getFirst().longValue(), theirs.path("content").get(0).path("orderId").asLong());
        var all = get("/api/v1/admin/orders?size=50", admin, 200); assertEquals(3, all.path("totalElements").asInt());
        for (var row : all.path("content")) {
            assertEquals(Set.of("orderId", "memberId", "memberEmail", "status", "paymentOperationStatus", "cancelRequested", "totalPrice", "usedMileage", "orderDate", "trackingNo", "itemCount", "firstProductName"), row.propertyNames());
            assertEquals(300, row.path("usedMileage").asInt());
            long id = row.path("orderId").asLong(); Member member = ownerIds.contains(id) ? owner : other;
            assertEquals(member.getId().longValue(), row.path("memberId").asLong()); assertEquals(member.getEmail(), row.path("memberEmail").asText());
            assertEquals(jdbc.queryForObject("select tracking_no from orders where order_id=?", String.class, id), row.path("trackingNo").asText());
        }
    }
}

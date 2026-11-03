package com.back.coffeeprod.dev.order;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.cart.dto.CartDto;
import com.back.coffeeprod.domain.cart.entity.*;
import com.back.coffeeprod.domain.cart.service.CartService;
import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.back.coffeeprod.domain.payment.service.PaymentWorkflow;
import com.back.coffeeprod.domain.product.dto.ProductDto;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.product.service.ProductService;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/** Synthetic fixtures only; the same public-service scenarios run before and after DEV9-6. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false", "app.payment.workers-enabled=false",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.back.coffeeprod.dev.order.DevTask9OrderCreationIntegrationTest$SqlProbe",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password=dev9-unused",
        "jwt.secret-key=dev9-order-creation-synthetic-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=dev9-unused", "pg.toss.secret-key=dev9-unused"
})
class DevTask9OrderCreationIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ThreadLocal<Observation> ACTIVE = new ThreadLocal<>();
    static final AtomicReference<CountDownLatch> LOCK_ATTEMPT = new AtomicReference<>();
    static final AtomicBoolean REJECT_ORDER_INSERT = new AtomicBoolean();
    static boolean postgres() { return System.getenv("DEV_TASK9_6_PG_URL") != null; }
    static boolean baseline() { return "true".equals(System.getenv("DEV_TASK9_6_BASELINE")); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("DEV_TASK9_6_PG_URL");
        if (postgres()) {
            var match = java.util.regex.Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:(\\d+)/devtask9_6_[a-z0-9_]+")
                    .matcher(url);
            if (!match.matches() || Integer.parseInt(match.group(1)) <= 1024
                    || Integer.parseInt(match.group(1)) > 65535 || Integer.parseInt(match.group(1)) == 5432
                    || System.getenv("DEV_TASK9_6_PG_PASSWORD") == null)
                throw new IllegalArgumentException("Dedicated localhost non-5432 DEV9-6 PostgreSQL required");
            UUID.fromString(System.getenv("DEV_TASK9_6_OWNER"));
        }
        registry.add("spring.datasource.url", () -> postgres() ? url
                : "jdbc:h2:mem:devtask9_6;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        registry.add("spring.datasource.driver-class-name", () -> postgres() ? "org.postgresql.Driver" : "org.h2.Driver");
        registry.add("spring.datasource.username", () -> postgres() ? "devtask9" : "sa");
        registry.add("spring.datasource.password", () -> postgres() ? System.getenv("DEV_TASK9_6_PG_PASSWORD") : "");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> postgres() ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> postgres() ? "validate" : "create-drop");
        registry.add("spring.flyway.enabled", () -> postgres());
    }

    @Autowired OrderService orders;
    @Autowired CartService carts;
    @Autowired ProductService products;
    @Autowired PaymentWorkflow payments;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ProductRepository productRepository;
    long owner, other, address, otherAddress;
    final List<Long> productIds = new ArrayList<>();

    <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s -> action.get()); }
    <T> T newTx(Supplier<T> action) {
        var tx = new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(s -> action.get());
    }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS), "concurrency checkpoint timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    static <T> T dto(Map<String, ?> values, Class<T> type) { return JSON.convertValue(values, type); }
    OrderDto.CreateRequest request(long shipping, int mileage) {
        return dto(Map.of("addressId", shipping, "usedMileage", mileage), OrderDto.CreateRequest.class);
    }

    @BeforeEach void reset() {
        ACTIVE.remove(); LOCK_ATTEMPT.set(null); REJECT_ORDER_INSERT.set(false); productIds.clear();
        for (String table : List.of("payment_operation", "sales_rebuild_request", "payment", "order_item", "orders",
                "cart_item", "cart", "address", "product", "category", "member")) jdbc.update("delete from " + table);
        tx(() -> {
            var member = member("owner"); owner = member.getId(); address = address(member);
            var another = member("other"); other = another.getId(); otherAddress = address(another);
            var category = Category.builder().name("주문 생성 합성 카테고리").build(); em.persist(category);
            for (int i = 0; i < 20; i++) {
                var product = Product.builder().category(category).sku("D96-" + i).name("합성 원두 " + i)
                        .price(1000 + i * 100).weightGrams(200).stockQuantity(50).roastLevel(RoastLevel.MEDIUM)
                        .imageUrl("https://assets-coffeeprod.ttagyulab.com/products/catalog/so001-l-v1.webp").build();
                em.persist(product); productIds.add(product.getId());
            }
            return null;
        });
        doAnswer(invocation -> {
            var active = ACTIVE.get(); if (active != null) active.lockIds.add(invocation.getArgument(0));
            return mockingDetails(productRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        }).when(productRepository).findLockedById(anyLong());
        doAnswer(invocation -> {
            var active = ACTIVE.get(); if (active != null) {
                Long id = invocation.getArgument(0); int quantity = invocation.getArgument(1);
                assertNull(active.quantities.put(id, quantity), "one conditional mutation per unique product");
            }
            return mockingDetails(productRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        }).when(productRepository).decreaseStockIfEnough(anyLong(), anyInt());
    }
    Member member(String name) {
        var result = Member.builder().email(name + "@test.invalid").nickname(name).name("합성 회원")
                .password("unused").role(Role.USER).build(); result.addMileage(5000); em.persist(result); return result;
    }
    long address(Member member) {
        var result = Address.builder().member(member).recipient("합성 수령인").phone("01000000000")
                .zipcode("00000").addressLine1("테스트 주소").addressLine2("상세").isDefault(true).build();
        em.persist(result); return result.getId();
    }
    void cart(long member, List<Long> ids, int quantity, boolean extraOption, boolean reverse) {
        tx(() -> {
            var cart = Cart.builder().member(em.getReference(Member.class, member)).build(); em.persist(cart);
            var insertion = new ArrayList<>(ids); if (reverse) Collections.reverse(insertion);
            for (Long id : insertion) cart.addItem(CartItem.builder().cart(cart).product(em.getReference(Product.class, id))
                    .quantity(quantity).grindType(GrindType.WHOLE_BEAN).build());
            if (extraOption) cart.addItem(CartItem.builder().cart(cart).product(em.getReference(Product.class, ids.getFirst()))
                    .quantity(1).grindType(GrindType.DRIP).build());
            em.flush(); return null;
        });
    }
    long number(String sql, Object... values) { return jdbc.queryForObject(sql, Long.class, values); }
    Map<String, Object> state() {
        var result = new LinkedHashMap<String, Object>();
        for (String table : List.of("member", "product", "cart", "cart_item", "orders", "order_item"))
            result.put(table, jdbc.queryForList("select * from " + table + " order by 1"));
        return result;
    }
    Observed create(long member, long shipping, int mileage) {
        var observation = new Observation(); ACTIVE.set(observation); long started = System.nanoTime();
        try { return new Observed(orders.createOrder(member, request(shipping, mileage)), observation, System.nanoTime() - started); }
        finally { ACTIVE.remove(); }
    }

    @ParameterizedTest @ValueSource(ints = {1, 5, 20})
    void createMeasurementsAndSnapshots(int count) {
        var ids = productIds.subList(0, count); cart(owner, ids, 2, true, true);
        var result = create(owner, address, 300); var order = result.response(); var observed = result.observation();
        int total = ids.stream().mapToInt(id -> 2 * (1000 + productIds.indexOf(id) * 100)).sum() + 1000;
        assertEquals(total, order.getProductTotalPrice()); assertEquals(total >= 30000 ? 0 : 3000, order.getDeliveryFee());
        assertEquals(total - 300 + order.getDeliveryFee(), order.getTotalPrice());
        assertEquals(count + 1, order.getOrderItems().size()); assertEquals(4700, number("select mileage from member where member_id=?", owner));
        assertEquals(0, number("select count(*) from cart_item")); assertEquals(count + 1, number("select count(*) from order_item"));
        assertEquals(ids, observed.lockIds); assertEquals(ids, new ArrayList<>(observed.quantities.keySet()));
        for (int i = 0; i < count; i++) {
            assertEquals(i == 0 ? 3 : 2, observed.quantities.get(ids.get(i)));
            assertEquals(i == 0 ? 47 : 48, number("select stock_quantity from product where product_id=?", ids.get(i)));
        }
        for (var line : order.getOrderItems()) {
            int price = 1000 + productIds.indexOf(line.getProductId()) * 100;
            assertEquals(price, line.getOrderPrice()); assertEquals(price * line.getQuantity(), line.getSubTotal());
            assertNull(line.getOrderItemId(), "existing creation response precedes order-item flush");
            assertEquals("합성 원두 " + productIds.indexOf(line.getProductId()), line.getProductName());
        }
        long reads = observed.sql.stream().filter(sql -> sql.startsWith("select ") && sql.contains(" from product ")).count();
        long updates = observed.sql.stream().filter(sql -> sql.startsWith("update product ")).count();
        assertEquals(count, updates); assertEquals(count, observed.sql.stream().filter(sql -> sql.startsWith("update product ") && sql.contains(">=")).count());
        System.out.printf("DEV9_CREATE_METRICS database=%s baseline=%s products=%d sql=%d productSelects=%d conditionalUpdates=%d nanos=%d%n",
                postgres() ? "PostgreSQL" : "H2", baseline(), count, observed.sql.size(), reads, updates, result.nanos());
        if (!baseline()) assertEquals(2 * count, reads, "retain lock + current-state read; omit post-mutation refresh");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void managedStateAndReadOnlySettingsArePreserved(boolean readOnly) {
        cart(owner, productIds.subList(0, 1), 2, true, false);
        tx(() -> {
            var product = em.find(Product.class, productIds.getFirst()); var member = em.find(Member.class, owner);
            var unrelated = em.find(Member.class, other); var unrelatedProduct = em.find(Product.class, productIds.getLast());
            var session = em.unwrap(Session.class); boolean defaultReadOnly = session.isDefaultReadOnly();
            session.setReadOnly(product, readOnly); unrelatedProduct.updateStatus(ProductStatus.SOLD_OUT);
            create(owner, address, 300);
            assertTrue(em.contains(product)); assertTrue(em.contains(member)); assertTrue(em.contains(unrelated)); assertTrue(em.contains(unrelatedProduct));
            assertEquals(47, product.getStockQuantity()); assertEquals(4700, member.getMileage());
            assertEquals(readOnly, session.isReadOnly(product)); assertEquals(defaultReadOnly, session.isDefaultReadOnly());
            product.updateStatus(ProductStatus.HIDDEN); em.flush();
            assertEquals(47, number("select stock_quantity from product where product_id=?", product.getId()));
            assertEquals(readOnly ? "ON_SALE" : "HIDDEN", jdbc.queryForObject("select status from product where product_id=?", String.class, product.getId()));
            assertEquals("SOLD_OUT", jdbc.queryForObject("select status from product where product_id=?", String.class, unrelatedProduct.getId()));
            return null;
        });
    }

    @Test void staleContextReadsLatestLockedPriceNameAndStock() {
        cart(owner, productIds.subList(0, 1), 2, true, false);
        tx(() -> {
            var product = em.find(Product.class, productIds.getFirst()); assertEquals(1000, product.getPrice());
            newTx(() -> { jdbc.update("update product set price=2300,name='최신 원두',stock_quantity=7 where product_id=?", product.getId()); return null; });
            var result = create(owner, address, 300).response();
            assertEquals(6900, result.getProductTotalPrice()); assertEquals(2300, result.getOrderItems().getFirst().getOrderPrice());
            assertEquals("최신 원두", result.getOrderItems().getFirst().getProductName()); assertEquals(4, product.getStockQuantity());
            product.updateStatus(ProductStatus.HIDDEN); em.flush();
            assertEquals(4, number("select stock_quantity from product where product_id=?", product.getId()));
            return null;
        });
    }

    @Test void lateStockFailureRollsBackEveryMutationAndRetainsCart() {
        cart(owner, productIds.subList(0, 5), 2, true, true);
        jdbc.update("update product set stock_quantity=1 where product_id=?", productIds.get(4)); var before = state();
        var failure = assertThrows(CustomException.class, () -> create(owner, address, 300));
        assertEquals(ErrorCode.OUT_OF_STOCK, failure.getErrorCode()); assertEquals(before, state());
    }

    @Test void repeatedCartAddsAndDifferentGrindOptionsShareOneStockMutation() {
        cart(owner, productIds.subList(0, 1), 2, true, false);
        carts.addItem(owner, dto(Map.of("productId", productIds.getFirst(), "quantity", 2, "grindType", "WHOLE_BEAN"), CartDto.AddRequest.class));
        var result = create(owner, address, 0);
        assertEquals(2, result.response().getOrderItems().size()); assertEquals(5, result.observation().quantities.get(productIds.getFirst()));
        assertEquals(5, result.response().getOrderItems().stream().mapToInt(r -> r.getQuantity()).sum());
        assertEquals(45, number("select stock_quantity from product where product_id=?", productIds.getFirst()));
    }

    @Test void failureAfterAllStockMutationsRollsBackMileageStockAndCart() {
        cart(owner, productIds.subList(0, 5), 2, true, true); var before = state();
        REJECT_ORDER_INSERT.set(true);
        try {
            var failure = assertThrows(RuntimeException.class, () -> create(owner, address, 300));
            assertTrue(failure.toString().contains("SYNTHETIC_ORDER_INSERT_FAILURE"));
            assertFalse(REJECT_ORDER_INSERT.get(), "failure was injected only after every stock mutation");
        } finally { REJECT_ORDER_INSERT.set(false); }
        assertEquals(before, state());
    }

    @Test void purchaseWaitsForProductLockAndThenUsesCommittedValues() throws Exception {
        cart(owner, productIds.subList(0, 1), 2, true, false);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempt = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> tx(() -> {
                productRepository.findLockedById(productIds.getFirst()).orElseThrow();
                jdbc.update("update product set price=2300,stock_quantity=7 where product_id=?", productIds.getFirst());
                held.countDown(); await(release); return null;
            }));
            Future<Observed> buyer;
            try {
                await(held); LOCK_ATTEMPT.set(attempt); buyer = pool.submit(() -> create(owner, address, 300)); await(attempt);
                assertThrows(TimeoutException.class, () -> buyer.get(150, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); LOCK_ATTEMPT.set(null); }
            holder.get(15, TimeUnit.SECONDS); var order = buyer.get(15, TimeUnit.SECONDS).response();
            assertEquals(6900, order.getProductTotalPrice()); assertEquals(4, number("select stock_quantity from product where product_id=?", productIds.getFirst()));
        }
    }

    @Test void twoMembersCompeteForLastStockWithoutDoubleMileageUse() throws Exception {
        var ids = productIds.subList(0, 1); cart(owner, ids, 1, false, false); cart(other, ids, 1, false, false);
        jdbc.update("update product set stock_quantity=1 where product_id=?", ids.getFirst());
        var start = new CountDownLatch(1); var results = new ArrayList<Object>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> compete(start, owner, address)); var b = pool.submit(() -> compete(start, other, otherAddress));
            start.countDown(); results.add(a.get(15, TimeUnit.SECONDS)); results.add(b.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, results.stream().filter(r -> r instanceof Observed).count());
        assertEquals(1, results.stream().filter(r -> r == ErrorCode.OUT_OF_STOCK).count());
        assertEquals(0, number("select stock_quantity from product where product_id=?", ids.getFirst()));
        assertEquals(9700, number("select sum(mileage) from member")); assertEquals(1, number("select count(*) from orders"));
        assertEquals(1, number("select count(*) from cart_item"));
    }
    Object compete(CountDownLatch start, long member, long shipping) {
        await(start);
        try { return create(member, shipping, 300); }
        catch (CustomException e) { return e.getErrorCode(); }
    }

    @Test void sameMemberDuplicatePurchaseConsumesCartAndMileageOnce() throws Exception {
        cart(owner, productIds.subList(0, 1), 1, false, false); var start = new CountDownLatch(1);
        List<Object> results;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> compete(start, owner, address)); var b = pool.submit(() -> compete(start, owner, address));
            start.countDown(); results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, results.stream().filter(r -> r instanceof Observed).count());
        assertEquals(1, results.stream().filter(r -> r == ErrorCode.EMPTY_CART).count());
        assertEquals(49, number("select stock_quantity from product where product_id=?", productIds.getFirst()));
        assertEquals(4700, number("select mileage from member where member_id=?", owner)); assertEquals(1, number("select count(*) from orders"));
    }

    @Test void oppositeCartInsertionOrdersUseAscendingProductLocks() throws Exception {
        var ids = productIds.subList(0, 5); cart(owner, ids, 1, false, false); cart(other, ids, 1, false, true);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { await(start); return create(owner, address, 0); });
            var b = pool.submit(() -> { await(start); return create(other, otherAddress, 0); }); start.countDown();
            assertEquals(ids, a.get(15, TimeUnit.SECONDS).observation().lockIds);
            assertEquals(ids, b.get(15, TimeUnit.SECONDS).observation().lockIds);
        }
        for (Long id : ids) assertEquals(48, number("select stock_quantity from product where product_id=?", id));
    }

    @Test void refundIntakeAndPurchasePreserveStockAndMileage() throws Exception {
        var ids = productIds.subList(0, 1); cart(owner, ids, 2, false, false); cart(other, ids, 3, false, false);
        jdbc.update("update product set stock_quantity=10 where product_id=?", ids.getFirst());
        var order = create(owner, address, 500).response();
        assertEquals(200, payments.confirm(owner, dto(Map.of("paymentKey", "d96-synthetic", "tossOrderId", order.getTossOrderId(), "amount", order.getTotalPrice()), PaymentDto.ConfirmRequest.class)).status());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var refund = pool.submit(() -> { await(start); return payments.cancel(owner, order.getOrderId(), false); });
            var intake = pool.submit(() -> { await(start); return products.addStock(ids.getFirst(), dto(Map.of("quantity", 5), ProductDto.StockRequest.class)); });
            var purchase = pool.submit(() -> { await(start); return create(other, otherAddress, 300); }); start.countDown();
            assertEquals(200, refund.get(15, TimeUnit.SECONDS).status()); assertNotNull(intake.get(15, TimeUnit.SECONDS));
            assertEquals(3000, purchase.get(15, TimeUnit.SECONDS).response().getProductTotalPrice());
        }
        assertEquals(12, number("select stock_quantity from product where product_id=?", ids.getFirst()));
        assertEquals(5000, number("select mileage from member where member_id=?", owner));
        assertEquals(4700, number("select mileage from member where member_id=?", other));
        assertEquals("REFUNDED", jdbc.queryForObject("select status from payment", String.class));
    }

    static class Observation {
        final List<String> sql = new ArrayList<>();
        final List<Long> lockIds = new ArrayList<>();
        final Map<Long, Integer> quantities = new LinkedHashMap<>();
    }
    record Observed(OrderDto.DetailResponse response, Observation observation, long nanos) { }
    public static class SqlProbe implements StatementInspector {
        @Override public String inspect(String sql) {
            var observation = ACTIVE.get(); if (observation != null) {
                String normalized = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); observation.sql.add(normalized);
                if (normalized.startsWith("insert into orders ") && REJECT_ORDER_INSERT.compareAndSet(true, false)) {
                    assertEquals(5, observation.quantities.size());
                    throw new IllegalStateException("SYNTHETIC_ORDER_INSERT_FAILURE");
                }
                var attempt = LOCK_ATTEMPT.get();
                if (attempt != null && normalized.startsWith("select ") && normalized.contains(" from product ") && normalized.contains(" for ")) attempt.countDown();
            }
            return sql;
        }
    }
}

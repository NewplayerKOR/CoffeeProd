package com.back.coffeeprod.dev.cart;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.cart.dto.CartDto;
import com.back.coffeeprod.domain.cart.entity.Cart;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.cart.service.CartService;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.product.entity.Category;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real service/transaction/concurrency checks against explicitly isolated synthetic data. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=5000",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.back.coffeeprod.dev.cart.DevTask9CartReadIntegrationTest$SqlProbe",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password=dev9-unused",
        "jwt.secret-key=dev9-cart-synthetic-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=dev9-unused", "pg.toss.secret-key=dev9-unused", "app.payment.workers-enabled=false"
})
class DevTask9CartReadIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("DEV_TASK9_2_PG_URL"); boolean pg = url != null;
        if (pg) {
            var match = java.util.regex.Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:(\\d+)/devtask9_2_[a-z0-9_]+")
                    .matcher(url);
            if (!match.matches() || Integer.parseInt(match.group(1)) <= 1024
                    || Integer.parseInt(match.group(1)) > 65535 || Integer.parseInt(match.group(1)) == 5432
                    || System.getenv("DEV_TASK9_2_PG_PASSWORD") == null) {
                throw new IllegalArgumentException("DEV9-2 requires dedicated localhost non-5432 PostgreSQL");
            }
            UUID.fromString(System.getenv("DEV_TASK9_2_OWNER"));
        }
        registry.add("spring.datasource.url", () -> pg ? url
                : "jdbc:h2:mem:devtask9_2;MODE=PostgreSQL;LOCK_TIMEOUT=3000;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        registry.add("spring.datasource.driver-class-name", () -> pg ? "org.postgresql.Driver" : "org.h2.Driver");
        registry.add("spring.datasource.username", () -> pg ? "devtask9" : "sa");
        registry.add("spring.datasource.password", () -> pg ? System.getenv("DEV_TASK9_2_PG_PASSWORD") : "");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> pg ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> pg ? "validate" : "create-drop");
        registry.add("spring.flyway.enabled", () -> pg);
    }

    @Autowired CartService carts;
    @Autowired OrderService orders;
    @Autowired CartRepository cartRepository;
    @Autowired MemberRepository members;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    long owner, other, product, address;

    <T> T tx(Supplier<T> work) { return new TransactionTemplate(manager).execute(s -> work.get()); }
    <T> T dto(Object value, Class<T> type) { return JSON.convertValue(value, type); }
    CartDto.AddRequest addRequest(int quantity) {
        return dto(Map.of("productId", product, "quantity", quantity, "grindType", "WHOLE_BEAN"), CartDto.AddRequest.class);
    }
    OrderDto.DetailResponse createOrder() {
        return orders.createOrder(owner, dto(Map.of("addressId", address, "usedMileage", 1000), OrderDto.CreateRequest.class));
    }
    long number(String sql) { return jdbc.queryForObject(sql, Long.class); }

    @BeforeEach
    void fixture() {
        SqlProbe.GATE.set(null); SqlProbe.MEMBER_ATTEMPT.set(null); SqlProbe.EVENTS.clear();
        for (String table : List.of("order_item", "orders", "cart_item", "cart", "address", "product", "category", "member")) {
            jdbc.update("delete from " + table);
        }
        tx(() -> {
            var member = Member.builder().email("owner@test.invalid").password("unused").name("합성 회원")
                    .nickname("owner").role(Role.USER).build(); member.addMileage(5000); em.persist(member); owner = member.getId();
            var another = Member.builder().email("other@test.invalid").password("unused").name("다른 합성 회원")
                    .nickname("other").role(Role.USER).build(); em.persist(another); other = another.getId();
            var category = Category.builder().name("합성 카테고리").build(); em.persist(category);
            var item = Product.builder().category(category).sku("DEV9-CART").name("합성 원두").price(12000)
                    .weightGrams(200).stockQuantity(20).roastLevel(RoastLevel.MEDIUM)
                    .imageUrl("https://assets-coffeeprod.ttagyulab.com/products/catalog/so001-l-v1.webp").build();
            em.persist(item); product = item.getId();
            var shipping = Address.builder().member(member).recipient("합성 수령인").phone("01000000000")
                    .zipcode("00000").addressLine1("테스트 주소").isDefault(true).build();
            em.persist(shipping); address = shipping.getId(); return null;
        });
        SqlProbe.EVENTS.clear();
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS), "concurrency checkpoint timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    Future<?> holdMember(ExecutorService pool, CountDownLatch held, CountDownLatch release, boolean createCart) {
        return pool.submit(() -> tx(() -> {
            var member = members.findLockedById(owner).orElseThrow();
            if (createCart) { em.persist(Cart.builder().member(member).build()); em.flush(); }
            held.countDown(); await(release); return null;
        }));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twentyReadsAndFirstCreationCompleteWithFourConnections(boolean existing) throws Exception {
        if (existing) carts.addItem(owner, addRequest(1));
        SqlProbe.EVENTS.clear(); SqlProbe.GATE.set(new SqlProbe.ReadGate());
        try (var pool = Executors.newFixedThreadPool(20)) {
            var start = new CountDownLatch(1); var results = new ArrayList<Future<CartDto.CartResponse>>();
            for (int i = 0; i < 20; i++) results.add(pool.submit(() -> { await(start); return carts.getCart(owner); }));
            start.countDown();
            for (var result : results) {
                var response = result.get(15, TimeUnit.SECONDS);
                assertEquals(existing ? 1 : 0, response.getTotalQuantity());
                assertEquals(existing ? 12000 : 0, response.getTotalPrice());
            }
        } finally { SqlProbe.GATE.set(null); }
        var statements = List.copyOf(SqlProbe.EVENTS);
        assertEquals(1, number("select count(*) from cart where member_id=" + owner));
        long inserts = statements.stream().filter(row -> row.sql().startsWith("insert into cart (")).count();
        assertEquals(existing ? 0 : 1, inserts);
        if (existing) {
            assertEquals(20, statements.size());
            assertTrue(statements.stream().allMatch(row -> row.active() && row.readOnly() && !row.locking()));
        } else {
            assertTrue(statements.stream().anyMatch(row -> row.locking() && row.active() && !row.readOnly()));
        }
        System.out.printf("DEV9_CART database=%s existing=%s readers=20 pool=4 inserts=%d lockingStatements=%d%n",
                System.getenv("DEV_TASK9_2_PG_URL") == null ? "H2" : "PostgreSQL", existing, inserts,
                statements.stream().filter(SqlProbe.Event::locking).count());
    }

    @Test
    void existingReadsFinishWhileMemberLockIsStillHeld() throws Exception {
        carts.addItem(owner, addRequest(1));
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(21)) {
            var holder = holdMember(pool, held, release, false);
            try {
                await(held); SqlProbe.EVENTS.clear();
                var reads = new ArrayList<Future<CartDto.CartResponse>>();
                for (int i = 0; i < 20; i++) reads.add(pool.submit(() -> carts.getCart(owner)));
                for (var read : reads) assertEquals(1, read.get(10, TimeUnit.SECONDS).getTotalQuantity());
                assertFalse(holder.isDone(), "reads completed before the member lock was released");
                assertTrue(SqlProbe.EVENTS.stream().noneMatch(SqlProbe.Event::locking));
            } finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "update", "delete", "clear", "order"})
    void writesAndOrderStillWaitForMemberLock(String action) throws Exception {
        carts.addItem(owner, addRequest(1));
        long itemId = number("select min(cart_item_id) from cart_item");
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempt = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = holdMember(pool, held, release, false);
            Future<?> writer;
            try {
                await(held); SqlProbe.MEMBER_ATTEMPT.set(attempt);
                writer = pool.submit(() -> switch (action) {
                    case "add" -> carts.addItem(owner, addRequest(1));
                    case "update" -> carts.updateItem(owner, itemId,
                            dto(Map.of("quantity", 2, "grindType", "DRIP"), CartDto.UpdateRequest.class));
                    case "delete" -> carts.deleteItem(owner, itemId);
                    case "clear" -> { carts.clearCart(owner); yield null; }
                    case "order" -> createOrder();
                    default -> throw new IllegalArgumentException(action);
                });
                await(attempt);
                assertThrows(TimeoutException.class, () -> writer.get(200, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); SqlProbe.MEMBER_ATTEMPT.set(null); }
            holder.get(10, TimeUnit.SECONDS); writer.get(10, TimeUnit.SECONDS);
        }
        assertEquals(action.equals("add") || action.equals("update") ? 2 : 0, carts.getCart(owner).getTotalQuantity());
        assertEquals(action.equals("order") ? 19 : 20, number("select stock_quantity from product where product_id=" + product));
        assertEquals(action.equals("order") ? 4000 : 5000, number("select mileage from member where member_id=" + owner));
    }

    @Test
    void creationRechecksAfterWaitingForConcurrentCreator() throws Exception {
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempt = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = holdMember(pool, held, release, true);
            Future<CartDto.CartResponse> reader;
            try {
                await(held); SqlProbe.EVENTS.clear(); SqlProbe.MEMBER_ATTEMPT.set(attempt);
                reader = pool.submit(() -> carts.getCart(owner)); await(attempt);
                assertThrows(TimeoutException.class, () -> reader.get(200, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); SqlProbe.MEMBER_ATTEMPT.set(null); }
            holder.get(10, TimeUnit.SECONDS); assertEquals(0, reader.get(10, TimeUnit.SECONDS).getTotalQuantity());
        }
        assertEquals(1, number("select count(*) from cart where member_id=" + owner));
        assertTrue(SqlProbe.EVENTS.stream().noneMatch(row -> row.sql().startsWith("insert into cart (")));
    }

    @Test
    void concurrentMembersReadOnlyTheirOwnItems() throws Exception {
        carts.addItem(owner, addRequest(1)); carts.addItem(other, addRequest(2));
        try (var pool = Executors.newFixedThreadPool(20)) {
            var responses = new ArrayList<Future<CartDto.CartResponse>>();
            for (int i = 0; i < 20; i++) { long who = i % 2 == 0 ? owner : other; responses.add(pool.submit(() -> carts.getCart(who))); }
            for (int i = 0; i < responses.size(); i++) assertEquals(i % 2 == 0 ? 1 : 2, responses.get(i).get(10, TimeUnit.SECONDS).getTotalQuantity());
        }
        assertNotEquals(cartRepository.findByMemberId(owner).orElseThrow().getId(), cartRepository.findByMemberId(other).orElseThrow().getId());
    }

    @Test
    void missingMemberKeepsExistingErrorAndCreatesNoCart() {
        var error = assertThrows(CustomException.class, () -> carts.getCart(-1L));
        assertEquals(ErrorCode.MEMBER_NOT_FOUND, error.getErrorCode()); assertEquals(0, cartRepository.count());
    }

    @Test
    void twentyReadsRaceWithOrderWithoutChangingStockOrMileageTwice() throws Exception {
        carts.addItem(owner, addRequest(1));
        try (var pool = Executors.newFixedThreadPool(21)) {
            var start = new CountDownLatch(1); var readers = new ArrayList<Future<CartDto.CartResponse>>();
            for (int i = 0; i < 20; i++) readers.add(pool.submit(() -> { await(start); return carts.getCart(owner); }));
            var order = pool.submit(() -> { await(start); return createOrder(); }); start.countDown();
            assertNotNull(order.get(15, TimeUnit.SECONDS));
            for (var reader : readers) { var result = reader.get(15, TimeUnit.SECONDS);
                assertTrue(result.getTotalQuantity() == 0 || result.getTotalQuantity() == 1);
                assertEquals(result.getTotalQuantity() * 12000, result.getTotalPrice());
            }
        }
        assertEquals(1, number("select count(*) from orders")); assertEquals(1, number("select count(*) from order_item"));
        assertEquals(19, number("select stock_quantity from product where product_id=" + product));
        assertEquals(4000, number("select mileage from member where member_id=" + owner));
        assertEquals(0, carts.getCart(owner).getTotalQuantity());
    }

    public static class SqlProbe implements StatementInspector {
        record Event(String sql, boolean active, boolean readOnly, boolean locking) {}
        static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
        static final AtomicReference<ReadGate> GATE = new AtomicReference<>();
        static final AtomicReference<CountDownLatch> MEMBER_ATTEMPT = new AtomicReference<>();
        static class ReadGate { final AtomicInteger calls = new AtomicInteger(); final CyclicBarrier barrier = new CyclicBarrier(4); }
        @Override public String inspect(String sql) {
            String normalized = sql.toLowerCase(Locale.ROOT);
            boolean locking = normalized.matches("(?s).*\\bfor (?:no key )?update\\b.*");
            EVENTS.add(new Event(normalized, TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly(), locking));
            var attempt = MEMBER_ATTEMPT.get();
            if (attempt != null && locking && normalized.contains("from member ")) attempt.countDown();
            var gate = GATE.get();
            if (gate != null && normalized.contains("from cart ") && gate.calls.getAndIncrement() < 4) {
                try { gate.barrier.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                catch (BrokenBarrierException | TimeoutException e) { throw new IllegalStateException(e); }
            }
            return sql;
        }
    }
}

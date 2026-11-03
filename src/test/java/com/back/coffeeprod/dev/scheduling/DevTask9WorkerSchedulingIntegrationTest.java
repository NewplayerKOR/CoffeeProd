package com.back.coffeeprod.dev.scheduling;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.cart.dto.CartDto;
import com.back.coffeeprod.domain.cart.service.CartService;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.payment.entity.PaymentOperation;
import com.back.coffeeprod.domain.payment.gateway.FakePaymentGateway;
import com.back.coffeeprod.domain.payment.gateway.GatewayPayment;
import com.back.coffeeprod.domain.payment.service.PaymentWorkflow;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.statistics.entity.SalesStatistics;
import com.back.coffeeprod.domain.statistics.repository.SalesStatisticsRepository;
import com.back.coffeeprod.domain.statistics.service.SalesRebuildService;
import com.back.coffeeprod.global.common.time.BusinessTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Actual @Scheduled workers, real workflow/transactions, and a gated synthetic PG boundary. */
@SpringBootTest
@ActiveProfiles("test")
@Import(DevTask9WorkerSchedulingIntegrationTest.GatewayConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password=dev9-unused",
        "jwt.secret-key=dev9-schedulers-synthetic-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=dev9-unused", "pg.toss.secret-key=dev9-unused",
        "app.payment.workers-enabled=true", "app.payment.recovery-delay=50", "app.statistics.rebuild-delay=50"
})
class DevTask9WorkerSchedulingIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final LocalDate HISTORY = LocalDate.of(2000, 1, 2);
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("DEV_TASK9_4_PG_URL"); boolean pg = url != null;
        if (pg) {
            var match = java.util.regex.Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:(\\d+)/devtask9_4_[a-z0-9_]+")
                    .matcher(url);
            if (!match.matches() || Integer.parseInt(match.group(1)) <= 1024 || Integer.parseInt(match.group(1)) > 65535
                    || Integer.parseInt(match.group(1)) == 5432 || System.getenv("DEV_TASK9_4_PG_PASSWORD") == null)
                throw new IllegalArgumentException("Dedicated DEV9-4 PostgreSQL required");
            UUID.fromString(System.getenv("DEV_TASK9_4_OWNER"));
        }
        registry.add("spring.datasource.url", () -> pg ? url
                : "jdbc:h2:mem:devtask9_4;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        registry.add("spring.datasource.driver-class-name", () -> pg ? "org.postgresql.Driver" : "org.h2.Driver");
        registry.add("spring.datasource.username", () -> pg ? "devtask9" : "sa");
        registry.add("spring.datasource.password", () -> pg ? System.getenv("DEV_TASK9_4_PG_PASSWORD") : "");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> pg ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> pg ? "validate" : "create-drop");
        registry.add("spring.flyway.enabled", () -> pg);
    }
    @Autowired PaymentWorkflow workflow;
    @Autowired OrderService orders;
    @Autowired CartService carts;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired SalesStatisticsRepository statistics;
    @Autowired GatedGateway gateway;
    @Autowired @Qualifier("paymentRecoveryTaskScheduler") ThreadPoolTaskScheduler paymentScheduler;
    @Autowired @Qualifier("salesStatisticsTaskScheduler") ThreadPoolTaskScheduler statisticsScheduler;
    @MockitoSpyBean SalesRebuildService rebuild;
    final Queue<String> statisticsThreads = new ConcurrentLinkedQueue<>();
    volatile boolean blockStatistics;
    final AtomicBoolean statisticsBlocked = new AtomicBoolean();
    final CountDownLatch statisticsEntered = new CountDownLatch(1), statisticsRelease = new CountDownLatch(1);

    <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s -> action.get()); }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS), "scheduler checkpoint timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    static void until(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!ready.getAsBoolean()) { assertTrue(System.nanoTime() < deadline, "scheduled work did not finish"); Thread.sleep(10); }
    }
    long number(String sql) { return jdbc.queryForObject(sql, Long.class); }

    @BeforeEach void isolatedFixtures() {
        for (String table : List.of("payment_operation", "sales_rebuild_request", "payment", "order_item", "orders",
                "cart_item", "cart", "address", "product", "category", "sales_statistics", "member")) jdbc.update("delete from " + table);
        doAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0); statisticsThreads.add(Thread.currentThread().getName());
            if (blockStatistics && date.equals(HISTORY) && statisticsBlocked.compareAndSet(false, true)) {
                statisticsEntered.countDown(); await(statisticsRelease);
            }
            return invocation.callRealMethod();
        }).when(rebuild).process(any(LocalDate.class));
    }

    record Fixture(long member, long product, long operation) {}
    List<Fixture> lostApprovals(int count) {
        var result = new ArrayList<Fixture>();
        for (int i = 0; i < count; i++) {
            long[] ids = tx(() -> {
                String suffix = UUID.randomUUID().toString();
                var member = Member.builder().email(suffix + "@test.invalid").password("unused").name("합성 회원")
                        .nickname(suffix).role(Role.USER).build(); member.addMileage(5000); em.persist(member);
                var category = Category.builder().name("합성 " + suffix).build(); em.persist(category);
                var product = Product.builder().category(category).sku("D9-" + suffix).name("합성 원두").price(20000)
                        .stockQuantity(20).weightGrams(200).roastLevel(RoastLevel.MEDIUM).build(); em.persist(product);
                var address = Address.builder().member(member).recipient("합성 수령인").phone("01000000000")
                        .zipcode("00000").addressLine1("합성 주소").build(); em.persist(address);
                return new long[]{member.getId(), product.getId(), address.getId()};
            });
            carts.addItem(ids[0], JSON.convertValue(Map.of("productId", ids[1], "quantity", 1, "grindType", "WHOLE_BEAN"), CartDto.AddRequest.class));
            var order = orders.createOrder(ids[0], JSON.convertValue(Map.of("addressId", ids[2], "usedMileage", 1000), OrderDto.CreateRequest.class));
            String key = "scheduler-" + UUID.randomUUID();
            gateway.confirm(key, order.getTossOrderId(), order.getTotalPrice(), order.getTossOrderId());
            long operation = tx(() -> {
                var operationEntity = new PaymentOperation(em.find(Orders.class, order.getOrderId()), PaymentOperation.Type.CONFIRM, key, "USER", ids[0]);
                operationEntity.claim(Instant.now(), Duration.ofSeconds(120), false); operationEntity.unknown("TEST_LOST_RESPONSE");
                em.persist(operationEntity); return operationEntity.getId();
            });
            result.add(new Fixture(ids[0], ids[1], operation));
        }
        return result;
    }
    void due(List<Fixture> fixtures) {
        String placeholders = String.join(",", Collections.nCopies(fixtures.size(), "?"));
        jdbc.update("update payment_operation set next_attempt_at=null where id in (" + placeholders + ")",
                fixtures.stream().map(Fixture::operation).toArray());
    }
    void requestHistory() {
        statistics.saveAndFlush(new SalesStatistics(HISTORY, 9, 9, 9, 9, 9));
        rebuild.request(BusinessTime.startOfDay(HISTORY));
    }
    void historyCompleted() throws InterruptedException {
        until(() -> jdbc.queryForObject("select count(*) from sales_rebuild_request where stat_date=?", Long.class, HISTORY) == 0
                && statistics.findByStatDate(HISTORY).map(row -> row.getPaymentAmount() == 0).orElse(false));
    }
    void recovered(List<Fixture> fixtures) throws InterruptedException {
        until(() -> number("select count(*) from payment_operation where status='SUCCEEDED'") == fixtures.size());
        until(() -> number("select count(*) from sales_rebuild_request") == 0);
        assertEquals(fixtures.size(), number("select count(*) from payment"));
        assertEquals(fixtures.size(), gateway.posts.get()); assertEquals(fixtures.size(), gateway.lookups.get());
        assertEquals(1, gateway.maxActive.get());
        for (var fixture : fixtures) {
            assertEquals(19, number("select stock_quantity from product where product_id=" + fixture.product));
            assertEquals(4190, number("select mileage from member where member_id=" + fixture.member));
        }
        assertTrue(gateway.threads.stream().allMatch(name -> name.startsWith("payment-recovery-")));
        assertFalse(statisticsThreads.isEmpty()); assertTrue(statisticsThreads.stream().allMatch(name -> name.startsWith("sales-statistics-")));
        assertEquals(1, paymentScheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
        assertEquals(1, statisticsScheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
    }

    @Test void blockedLookupAndBacklogDoNotDelayStatisticsAndLeasePreventsDuplicateCalls() throws Exception {
        var fixtures = lostApprovals(3); gateway.block = true; due(fixtures);
        try {
            await(gateway.entered);
            long id = gateway.currentOperation(jdbc);
            var lease = jdbc.queryForMap("select status,lease_token,lease_until,attempt_count,version from payment_operation where id=?", id);
            assertEquals("IN_FLIGHT", lease.get("status"));
            try (var pool = Executors.newFixedThreadPool(3)) {
                var duplicates = new ArrayList<Future<?>>();
                for (int i = 0; i < 3; i++) duplicates.add(pool.submit(() -> workflow.drive(id)));
                for (var duplicate : duplicates) duplicate.get(5, TimeUnit.SECONDS);
            }
            assertEquals(lease, jdbc.queryForMap("select status,lease_token,lease_until,attempt_count,version from payment_operation where id=?", id));
            assertEquals(1, gateway.lookups.get()); assertEquals(3, gateway.posts.get());
            long queued = System.nanoTime(); requestHistory(); historyCompleted();
            assertEquals(1, gateway.release.getCount()); assertEquals(1, gateway.active.get());
            System.out.printf("DEV9_SCHEDULERS database=%s scope=blocked-payment backlog=3 statisticsQueueNanos=%d paymentThread=%s statisticsThread=%s%n",
                    System.getenv("DEV_TASK9_4_PG_URL") == null ? "H2" : "PostgreSQL", System.nanoTime() - queued,
                    gateway.threads.peek(), statisticsThreads.peek());
        } finally { gateway.block = false; gateway.release.countDown(); }
        recovered(fixtures);
    }

    @Test void blockedStatisticsDoesNotDelayPaymentRecovery() throws Exception {
        var fixtures = lostApprovals(1); blockStatistics = true; requestHistory();
        try {
            await(statisticsEntered); due(fixtures);
            until(() -> number("select count(*) from payment_operation where status='SUCCEEDED'") == 1);
            assertEquals(1, statisticsRelease.getCount());
            assertEquals(1, jdbc.queryForObject("select count(*) from sales_rebuild_request where stat_date=?", Long.class, HISTORY));
            assertEquals(9, statistics.findByStatDate(HISTORY).orElseThrow().getPaymentAmount());
            System.out.printf("DEV9_SCHEDULERS database=%s scope=blocked-statistics paymentRecovered=true paymentThread=%s statisticsThread=%s%n",
                    System.getenv("DEV_TASK9_4_PG_URL") == null ? "H2" : "PostgreSQL", gateway.threads.peek(), statisticsThreads.peek());
        } finally { blockStatistics = false; statisticsRelease.countDown(); }
        historyCompleted(); recovered(fixtures);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfig { @Bean @Primary GatedGateway gatedGateway() { return new GatedGateway(); } }
    static class GatedGateway extends FakePaymentGateway {
        final AtomicInteger posts = new AtomicInteger(), lookups = new AtomicInteger(), active = new AtomicInteger(), maxActive = new AtomicInteger();
        final AtomicInteger approvalSequence = new AtomicInteger();
        final Map<String, Instant> approvedAt = new ConcurrentHashMap<>();
        final Queue<String> threads = new ConcurrentLinkedQueue<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean block;
        @Override public GatewayPayment confirm(String key, String order, int amount, String idempotency) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); posts.incrementAndGet();
            // Separate dates keep this scheduler test independent of H2's lack of advisory locks.
            approvedAt.computeIfAbsent(key, ignored -> Instant.parse("2001-01-01T00:00:00Z")
                    .plusSeconds(approvalSequence.getAndIncrement() * 86400L));
            return super.confirm(key, order, amount, idempotency);
        }
        @Override public GatewayPayment lookupByPaymentKey(String key) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); threads.add(Thread.currentThread().getName());
            lookups.incrementAndGet(); maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (block) { entered.countDown(); await(release); }
                var payment = super.lookupByPaymentKey(key);
                return payment == null ? null : new GatewayPayment(payment.paymentKey(), payment.orderId(), payment.status(),
                        payment.totalAmount(), payment.balanceAmount(), payment.method(), approvedAt.get(key), payment.cardBased(), payment.cancels());
            }
            finally { active.decrementAndGet(); }
        }
        long currentOperation(JdbcTemplate jdbc) {
            return jdbc.queryForObject("select id from payment_operation where status='IN_FLIGHT'", Long.class);
        }
    }
}

package com.back.coffeeprod.dev.statistics;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.cart.dto.CartDto;
import com.back.coffeeprod.domain.cart.service.CartService;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.back.coffeeprod.domain.payment.entity.Payment;
import com.back.coffeeprod.domain.payment.entity.PaymentStatus;
import com.back.coffeeprod.domain.payment.repository.PaymentRepository;
import com.back.coffeeprod.domain.payment.service.PaymentWorkflow;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.statistics.entity.SalesStatistics;
import com.back.coffeeprod.domain.statistics.entity.SalesStatisticsUnit;
import com.back.coffeeprod.domain.statistics.repository.SalesStatisticsRepository;
import com.back.coffeeprod.domain.statistics.service.*;
import com.back.coffeeprod.global.common.time.BusinessTime;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
@Import(DevTask9StatisticsRangeIntegrationTest.CaptureConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1", "spring.data.redis.password=dev9-unused",
        "jwt.secret-key=dev9-statistics-synthetic-key-at-least-32-bytes",
        "jwt.access-expiration=1800000", "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=dev9-unused", "pg.toss.secret-key=dev9-unused", "app.payment.workers-enabled=false"
})
class DevTask9StatisticsRangeIntegrationTest {
    static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicReference<Gate> GATE = new AtomicReference<>();
    final Queue<LocalDate> lockedDates = new ConcurrentLinkedQueue<>();
    @Autowired SalesStatisticsService sales;
    @Autowired SalesStatisticsRepository statistics;
    @Autowired SalesRebuildService rebuild;
    @Autowired PaymentWorkflow workflow;
    @Autowired PaymentRepository payments;
    @Autowired CartService carts;
    @Autowired OrderService orders;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean StatisticsDateLock dateLock;
    long memberId;

    static boolean postgres() { return System.getenv("DEV_TASK9_3_PG_URL") != null; }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("DEV_TASK9_3_PG_URL");
        if (postgres()) {
            var match = java.util.regex.Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:(\\d+)/devtask9_3_[a-z0-9_]+")
                    .matcher(url);
            if (!match.matches() || Integer.parseInt(match.group(1)) <= 1024
                    || Integer.parseInt(match.group(1)) > 65535 || Integer.parseInt(match.group(1)) == 5432
                    || System.getenv("DEV_TASK9_3_PG_PASSWORD") == null) throw new IllegalArgumentException("Dedicated DEV9-3 PostgreSQL required");
            UUID.fromString(System.getenv("DEV_TASK9_3_OWNER"));
        }
        registry.add("spring.datasource.url", () -> postgres() ? url
                : "jdbc:h2:mem:devtask9_3;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        registry.add("spring.datasource.driver-class-name", () -> postgres() ? "org.postgresql.Driver" : "org.h2.Driver");
        registry.add("spring.datasource.username", () -> postgres() ? "devtask9" : "sa");
        registry.add("spring.datasource.password", () -> postgres() ? System.getenv("DEV_TASK9_3_PG_PASSWORD") : "");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> postgres() ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> postgres() ? "validate" : "create-drop");
        registry.add("spring.flyway.enabled", () -> postgres());
    }

    <T> T tx(Supplier<T> work) { return new TransactionTemplate(manager).execute(s -> work.get()); }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS), "concurrency checkpoint timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    @BeforeEach void reset() {
        Probe.active = false; GATE.set(null); lockedDates.clear();
        for (String table : List.of("payment_operation", "sales_rebuild_request", "payment", "order_item", "orders",
                "cart_item", "cart", "address", "product", "category", "sales_statistics", "member")) jdbc.update("delete from " + table);
        memberId = tx(() -> {
            var member = Member.builder().email("statistics@test.invalid").password("unused").name("합성 회원")
                    .nickname("statistics").role(Role.USER).build(); member.addMileage(5000); em.persist(member); return member.getId();
        });
        doAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0); var gate = GATE.get();
            if (gate != null && Thread.currentThread() != gate.thread && !date.isBefore(gate.from) && !date.isAfter(gate.to)) {
                gate.contender.countDown();
            }
            Object result = invocation.callRealMethod(); lockedDates.add(date);
            if (gate != null && Thread.currentThread() == gate.thread && date.equals(gate.pause)) {
                gate.held.countDown(); await(gate.release);
            }
            return result;
        }).when(dateLock).acquire(any(LocalDate.class));
    }

    void payment(Instant paidAt, PaymentStatus status) {
        var member = em.find(Member.class, memberId); String suffix = UUID.randomUUID().toString();
        var order = Orders.builder().member(member).tossOrderId("DEV9-" + suffix).productTotalPrice(20000)
                .deliveryFee(3000).usedMileage(1000).totalPrice(22000).deliveryAddress("합성 주소").build(); em.persist(order);
        var payment = Payment.builder().orders(order).pgProvider("TOSSPAYMENTS").paymentKey("DEV9-" + suffix)
                .payMethod("CARD").status(status).build(); payment.recordApprovedAt(paidAt); em.persist(payment);
    }
    void seed(int days, String state) {
        tx(() -> {
            for (int i = 0; i < days; i++) {
                LocalDate date = FROM.plusDays(i);
                if (i % 3 == 0) payment(BusinessTime.startOfDay(date).plusSeconds(43200), PaymentStatus.SUCCESS);
                if (state.equals("existing") || state.equals("mixed") && i % 2 == 0) em.persist(new SalesStatistics(date, 9, 9, 9, 9, 9));
            }
            payment(BusinessTime.startOfDay(FROM), PaymentStatus.FAILED);
            payment(BusinessTime.startOfDay(FROM), PaymentStatus.REFUNDED);
            payment(BusinessTime.startOfDay(FROM.minusDays(1)), PaymentStatus.SUCCESS);
            payment(BusinessTime.startOfDay(FROM.plusDays(days)), PaymentStatus.SUCCESS);
            em.persist(new SalesStatistics(FROM.minusDays(1), 42, 42, 42, 42, 42)); return null;
        });
    }
    List<Map<String, Object>> snapshot(boolean ids) {
        return jdbc.queryForList("select " + (ids ? "sales_statistics_id," : "")
                + "stat_date,order_count,product_sales_amount,delivery_fee_amount,used_mileage_amount,payment_amount from sales_statistics order by stat_date");
    }
    void restore(List<Map<String, Object>> initial) {
        jdbc.update("delete from sales_statistics");
        for (var row : initial) jdbc.update("""
                insert into sales_statistics (sales_statistics_id,stat_date,order_count,product_sales_amount,delivery_fee_amount,used_mileage_amount,payment_amount)
                values (?,?,?,?,?,?,?)
                """, row.get("sales_statistics_id"), row.get("stat_date"), row.get("order_count"), row.get("product_sales_amount"),
                row.get("delivery_fee_amount"), row.get("used_mileage_amount"), row.get("payment_amount"));
    }

    @ParameterizedTest
    @CsvSource({"1,new", "1,existing", "1,mixed", "30,new", "30,existing", "30,mixed", "366,new", "366,existing", "366,mixed"})
    void rangeMatchesDailyReferenceAndUsesRealBatchWrites(int days, String state) {
        seed(days, state); var initial = snapshot(true);
        Probe.start();
        tx(() -> { for (int i = 0; i < days; i++) sales.aggregateDailySales(FROM.plusDays(i)); return null; });
        var legacy = Probe.stop(); var expected = snapshot(false); restore(initial);
        Probe.start(); var response = sales.aggregateSalesRange(FROM, FROM.plusDays(days - 1)); var optimized = Probe.stop();
        assertEquals(days, response.getAggregatedDays()); assertEquals(FROM, response.getFrom());
        assertEquals(FROM.plusDays(days - 1), response.getTo()); assertEquals(expected, snapshot(false));
        var stored = statistics.findByStatDateBetweenOrderByStatDateAsc(FROM, FROM.plusDays(days - 1));
        assertEquals(days, stored.size());
        for (int i = 0; i < days; i++) { assertEquals(i % 3 == 0 ? 1 : 0, stored.get(i).getOrderCount());
            assertEquals(i % 3 == 0 ? 22000 : 0, stored.get(i).getPaymentAmount()); }
        for (var row : initial) assertEquals(((Number) row.get("sales_statistics_id")).longValue(),
                jdbc.queryForObject("select sales_statistics_id from sales_statistics where stat_date=?", Long.class, row.get("stat_date")));
        long aggregates = optimized.stream().filter(e -> e.sql.contains("from payment") && e.sql.contains("group by")).count();
        long statReads = optimized.stream().filter(e -> e.sql.startsWith("select") && e.sql.contains("from sales_statistics")).count();
        long inserts = optimized.stream().filter(e -> e.sql.startsWith("insert into sales_statistics")).count();
        long batches = optimized.stream().filter(e -> e.method.equals("executeBatch")).count();
        assertEquals(1, aggregates); assertEquals(1, statReads);
        assertEquals((days - (state.equals("existing") ? days : state.equals("mixed") ? (days + 1) / 2 : 0) + 49) / 50, inserts);
        if (!state.equals("new")) assertTrue(batches > 0, "existing rows must use JDBC executeBatch");
        if (days > 1) assertTrue(optimized.size() < legacy.size());
        System.out.printf("DEV9_STATS database=%s days=%d state=%s legacyExecutions=%d optimizedExecutions=%d aggregateQueries=%d statisticsReads=%d insertStatements=%d updateBatches=%d%n",
                postgres() ? "PostgreSQL" : "H2", days, state, legacy.size(), optimized.size(), aggregates, statReads, inserts, batches);
    }

    @Test void kstMidnightLeapDayRefundAndCalendarTotalsStayConsistent() {
        LocalDate from = LocalDate.of(2024, 2, 28), to = from.plusDays(1);
        tx(() -> {
            for (String time : List.of("2024-02-27T14:59:59.999999Z", "2024-02-27T15:00:00Z", "2024-02-28T14:59:59.999999Z",
                    "2024-02-28T15:00:00Z", "2024-02-29T14:59:59.999999Z", "2024-02-29T15:00:00Z")) payment(Instant.parse(time), PaymentStatus.SUCCESS);
            payment(Instant.parse("2024-02-28T15:00:00Z"), PaymentStatus.REFUNDED);
            payment(Instant.parse("2024-02-28T15:00:00Z"), PaymentStatus.FAILED); return null;
        });
        sales.aggregateSalesRange(from, to);
        var daily = sales.getSalesStatistics(from, to, SalesStatisticsUnit.DAILY);
        assertEquals(List.of("2024-02-28", "2024-02-29"), daily.stream().map(row -> row.getLabel()).toList());
        for (var row : daily) { assertEquals(2, row.getOrderCount()); assertEquals(40000, row.getProductSalesAmount());
            assertEquals(6000, row.getDeliveryFeeAmount()); assertEquals(2000, row.getUsedMileageAmount()); assertEquals(44000, row.getPaymentAmount()); }
        assertEquals(88000, sales.getSalesStatistics(from, to, SalesStatisticsUnit.MONTHLY).getFirst().getPaymentAmount());
        assertEquals(88000, sales.getSalesStatistics(from, to, SalesStatisticsUnit.YEARLY).getFirst().getPaymentAmount());
        var before = snapshot(false); sales.aggregateDailySales(to); assertEquals(before, snapshot(false));
    }

    @ParameterizedTest
    @CsvSource({"30,new", "30,existing", "366,mixed"})
    void middleFailureRollsBackEarlierUpdatesAndInsertChunks(int days, String state) {
        seed(days, state); var before = snapshot(true); LocalDate fail = FROM.plusDays(days / 2);
        jdbc.execute("alter table sales_statistics add constraint dev9_range_failure check (stat_date <> DATE '" + fail + "' or payment_amount=9)");
        try { assertThrows(RuntimeException.class, () -> sales.aggregateSalesRange(FROM, FROM.plusDays(days - 1)));
            assertEquals(before, snapshot(true)); }
        finally { jdbc.execute("alter table sales_statistics drop constraint dev9_range_failure"); }
        sales.aggregateSalesRange(FROM, FROM.plusDays(days - 1));
        assertEquals(days, statistics.findByStatDateBetweenOrderByStatDateAsc(FROM, FROM.plusDays(days - 1)).size());
    }

    @Test void managedRowsAndSessionBatchSettingArePreserved() {
        seed(30, "mixed");
        tx(() -> {
            var session = em.unwrap(Session.class); session.setJdbcBatchSize(7);
            var managed = statistics.findByStatDate(FROM).orElseThrow();
            sales.aggregateSalesRange(FROM, FROM.plusDays(29));
            assertTrue(em.contains(managed)); assertSame(managed, statistics.findByStatDate(FROM).orElseThrow());
            assertEquals(22000, managed.getPaymentAmount()); assertEquals(7, session.getJdbcBatchSize()); return null;
        });
    }

    @Test void rangeAcquiresAscendingDateLocksAndKeepsThemUntilCommit() throws Exception {
        var gate = new Gate(FROM, FROM.plusDays(2), FROM.plusDays(1)); GATE.set(gate);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> { gate.thread = Thread.currentThread(); return sales.aggregateSalesRange(gate.from, gate.to); });
            try {
                await(gate.held); assertEquals(List.of(FROM, FROM.plusDays(1)), List.copyOf(lockedDates));
                assertEquals(0, statistics.count());
                if (postgres()) { assertFalse(tryLock(FROM)); assertFalse(tryLock(FROM.plusDays(1))); assertTrue(tryLock(FROM.plusDays(2))); }
            } finally { gate.release.countDown(); }
            result.get(15, TimeUnit.SECONDS);
            assertEquals(List.of(FROM, FROM.plusDays(1), FROM.plusDays(2)), List.copyOf(lockedDates));
        } finally { GATE.set(null); }
        if (postgres()) assertTrue(tryLock(FROM));
    }
    boolean tryLock(LocalDate date) {
        return tx(() -> jdbc.queryForObject("select pg_try_advisory_xact_lock(731904, ?)", Boolean.class, Math.toIntExact(date.toEpochDay())));
    }

    @Test void overlappingRangesConvergeWithoutDuplicateDatesOrDeadlock() throws Exception {
        seed(4, "existing");
        if (postgres()) {
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var a = pool.submit(() -> { await(start); return sales.aggregateSalesRange(FROM, FROM.plusDays(2)); });
                var b = pool.submit(() -> { await(start); return sales.aggregateSalesRange(FROM.plusDays(1), FROM.plusDays(3)); });
                start.countDown(); a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
            }
        } else { sales.aggregateSalesRange(FROM, FROM.plusDays(2)); sales.aggregateSalesRange(FROM.plusDays(1), FROM.plusDays(3)); }
        assertEquals(4, statistics.findByStatDateBetweenOrderByStatDateAsc(FROM, FROM.plusDays(3)).size());
        assertEquals(44000, sales.getSalesStatistics(FROM, FROM.plusDays(3), SalesStatisticsUnit.YEARLY).getFirst().getPaymentAmount());
    }

    OrderDto.DetailResponse workflowOrder() {
        long[] ids = tx(() -> {
            var member = em.find(Member.class, memberId); var category = Category.builder().name("통계 합성 카테고리").build(); em.persist(category);
            var product = Product.builder().category(category).sku("STATS-" + UUID.randomUUID()).name("합성 원두").price(20000)
                    .weightGrams(200).stockQuantity(20).roastLevel(RoastLevel.MEDIUM).build(); em.persist(product);
            var address = Address.builder().member(member).recipient("합성 수령인").phone("01000000000").zipcode("00000").addressLine1("합성 주소").build(); em.persist(address);
            return new long[]{product.getId(), address.getId()};
        });
        carts.addItem(memberId, JSON.convertValue(Map.of("productId", ids[0], "quantity", 1, "grindType", "WHOLE_BEAN"), CartDto.AddRequest.class));
        return orders.createOrder(memberId, JSON.convertValue(Map.of("addressId", ids[1], "usedMileage", 1000), OrderDto.CreateRequest.class));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void approvalAndRefundRebuildRequestsWaitForRangeAndThenConverge(boolean refund) throws Exception {
        var order = workflowOrder();
        var confirm = JSON.convertValue(Map.of("paymentKey", "STAT-" + UUID.randomUUID(), "tossOrderId", order.getTossOrderId(), "amount", order.getTotalPrice()), PaymentDto.ConfirmRequest.class);
        if (refund) assertEquals(200, workflow.confirm(memberId, confirm).status());
        LocalDate today = LocalDate.now(BusinessTime.SEOUL_ZONE); var gate = new Gate(today.minusDays(1), today.plusDays(1), today.plusDays(1)); GATE.set(gate);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var range = pool.submit(() -> { gate.thread = Thread.currentThread(); return sales.aggregateSalesRange(gate.from, gate.to); });
            Future<?> writer = null;
            try {
                await(gate.held);
                if (postgres()) {
                    writer = pool.submit(() -> refund ? workflow.cancel(memberId, order.getOrderId(), false) : workflow.confirm(memberId, confirm));
                    await(gate.contender); final Future<?> pending = writer;
                    assertThrows(TimeoutException.class, () -> pending.get(200, TimeUnit.MILLISECONDS));
                }
            } finally { gate.release.countDown(); }
            range.get(15, TimeUnit.SECONDS);
            if (writer != null) writer.get(15, TimeUnit.SECONDS);
            else assertEquals(200, (refund ? workflow.cancel(memberId, order.getOrderId(), false) : workflow.confirm(memberId, confirm)).status());
        } finally { GATE.set(null); }
        var payment = payments.findByOrdersId(order.getOrderId()).orElseThrow();
        LocalDate original = payment.getPaidAt().atZone(BusinessTime.SEOUL_ZONE).toLocalDate(); rebuild.process(original);
        assertEquals(refund ? 0 : 1, statistics.findByStatDate(original).orElseThrow().getOrderCount());
        assertEquals(refund ? 0 : order.getTotalPrice(), statistics.findByStatDate(original).orElseThrow().getPaymentAmount());
        assertEquals(0, jdbc.queryForObject("select count(*) from sales_rebuild_request", Long.class));
    }

    @Test void invalidRangesKeepErrorsAndDoNotWrite() {
        for (LocalDate[] range : List.of(new LocalDate[]{null, FROM}, new LocalDate[]{FROM, null}, new LocalDate[]{FROM.plusDays(1), FROM}))
            assertEquals(ErrorCode.INVALID_STATISTICS_DATE_RANGE, assertThrows(CustomException.class, () -> sales.aggregateSalesRange(range[0], range[1])).getErrorCode());
        assertEquals(ErrorCode.STATISTICS_DATE_RANGE_TOO_LARGE, assertThrows(CustomException.class, () -> sales.aggregateSalesRange(FROM, FROM.plusDays(366))).getErrorCode());
        assertEquals(0, statistics.count());
    }

    static class Gate {
        final LocalDate from, to, pause; volatile Thread thread;
        final CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), contender = new CountDownLatch(1);
        Gate(LocalDate from, LocalDate to, LocalDate pause) { this.from = from; this.to = to; this.pause = pause; }
    }

    /** Counts actual JDBC execution methods, including Hibernate update batches. No bind values are recorded. */
    static class Probe {
        record Execution(String sql, String method) {}
        static volatile boolean active;
        static final Queue<Execution> EVENTS = new ConcurrentLinkedQueue<>();
        static void start() { EVENTS.clear(); active = true; }
        static List<Execution> stop() { active = false; return List.copyOf(EVENTS); }
        interface After { Object apply(Method method, Object[] args, Object result) throws Exception; }
        static Object proxy(Class<?> type, Object target, After after) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
                try { return after.apply(method, args, method.invoke(target, args)); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            });
        }
        static Connection connection(Connection connection) {
            return (Connection) proxy(Connection.class, connection, (method, args, result) -> {
                if (!method.getName().equals("prepareStatement") || !(args[0] instanceof String sql)) return result;
                return proxy(PreparedStatement.class, result, (m, a, value) -> {
                    if (active && m.getName().startsWith("execute")) EVENTS.add(new Execution(sql.stripLeading().toLowerCase(Locale.ROOT), m.getName()));
                    return value;
                });
            });
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfig {
        @Bean static BeanPostProcessor countJdbcExecutions() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource)) return bean;
                    return Probe.proxy(DataSource.class, bean, (method, args, value) -> method.getName().equals("getConnection")
                            ? Probe.connection((Connection) value) : value);
                }
            };
        }
    }
}

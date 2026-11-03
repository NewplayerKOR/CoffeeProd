package com.back.coffeeprod.dev.indexes;

import com.back.coffeeprod.CoffeeprodApplication;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.payment.entity.PaymentStatus;
import com.back.coffeeprod.domain.product.service.ProductService;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.entity.RoastLevel;
import com.back.coffeeprod.domain.coffeeprofile.entity.BeanType;
import com.back.coffeeprod.domain.qna.entity.QnaStatus;
import com.back.coffeeprod.domain.qna.service.QnaService;
import com.back.coffeeprod.domain.review.service.ReviewService;
import com.back.coffeeprod.domain.statistics.entity.SalesStatisticsUnit;
import com.back.coffeeprod.domain.statistics.repository.PaymentSalesQueryRepository;
import com.back.coffeeprod.domain.statistics.service.SalesStatisticsService;
import org.flywaydb.core.Flyway;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.data.domain.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Standalone opt-in PostgreSQL experiment. Run with src/test/indexes/run.ps1, never on an operational DB. */
public final class DevTask9IndexProbe {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final LocalDate END = LocalDate.of(2026, 6, 30);
    static final Map<String, String> CANDIDATES = new LinkedHashMap<>();
    static {
        CANDIDATES.put("reviews", "CREATE INDEX idx_reviews_product_created_at ON reviews(product_id,created_at DESC)");
        CANDIDATES.put("qna", "CREATE INDEX idx_product_qna_product_created_at ON product_qna(product_id,created_at DESC)");
        CANDIDATES.put("qna-admin", "CREATE INDEX idx_product_qna_status_created_at ON product_qna(status,created_at DESC)");
        CANDIDATES.put("orders", "CREATE INDEX idx_orders_order_date_desc ON orders(order_date DESC)");
        CANDIDATES.put("payment", "CREATE INDEX idx_payment_status_paid_at ON payment(status,paid_at)");
    }
    record Scenario(String family, String name, List<QueryCapture.Query> queries) {}
    record Settings(String prefix, String password, Path output) {
        String url(String database) { return prefix + "devtask9_8_" + database; }
        Connection connect(String database) throws SQLException { return DriverManager.getConnection(url(database), "devtask9", password); }
    }
    static Settings settings() {
        String prefix = System.getenv("DEV_TASK9_8_PG_PREFIX");
        var m = java.util.regex.Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:(\\d+)/").matcher(prefix == null ? "" : prefix);
        if (!m.matches() || Integer.parseInt(m.group(1)) <= 1024 || Integer.parseInt(m.group(1)) > 65535 || Integer.parseInt(m.group(1)) == 5432)
            throw new IllegalArgumentException("Dedicated localhost non-5432 PostgreSQL required");
        UUID.fromString(System.getenv("DEV_TASK9_8_OWNER"));
        String password = System.getenv("DEV_TASK9_8_PG_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Synthetic credentials required");
        Path output = Path.of(System.getenv("DEV_TASK9_8_RESULTS")).toAbsolutePath().normalize();
        Path build = Path.of("build/dev-task9").toAbsolutePath().normalize();
        if (!output.startsWith(build)) throw new IllegalArgumentException("Evidence must stay under build/dev-task9");
        return new Settings(prefix, password, output);
    }
    public static void main(String[] args) throws Exception {
        var settings = settings();
        if (args.length == 0) throw new IllegalArgumentException("Expected migrate18, compare, final or migrate-final");
        if (args[0].startsWith("migrate")) {
            String db = args[0].equals("migrate18") ? "before" : "functional";
            var config = Flyway.configure().dataSource(settings.url(db), "devtask9", settings.password())
                    .locations("classpath:db/migration").cleanDisabled(true).validateOnMigrate(true);
            if (args[0].equals("migrate18")) config.target("18");
            var flyway = config.load(); var result = flyway.migrate(); flyway.validate();
            System.out.println("DEV9_INDEX_MIGRATE executed=" + result.migrationsExecuted);
            return;
        }
        boolean finalRun = args[0].equals("final");
        boolean coverRun = args[0].equals("compare-cover");
        if (!finalRun && !coverRun && !args[0].equals("compare")) throw new IllegalArgumentException("Unknown mode");
        try (var context = new SpringApplicationBuilder(CoffeeprodApplication.class, QueryCapture.Configuration.class)
                .profiles("test").run("--spring.profiles.active=test", "--server.port=0", "--management.server.port=0", "--logging.level.root=WARN",
                        "--spring.datasource.url=" + settings.url("before"), "--spring.datasource.username=devtask9",
                        "--spring.datasource.password=" + settings.password(), "--spring.datasource.driver-class-name=org.postgresql.Driver",
                        "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                        "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.enabled=false", "--spring.jpa.show-sql=false",
                        "--spring.jpa.open-in-view=false", "--app.payment.workers-enabled=false",
                        "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1", "--spring.data.redis.password=unused",
                        "--jwt.secret-key=dev9-index-synthetic-secret-key-at-least-32-bytes", "--jwt.access-expiration=1800000",
                        "--jwt.refresh-expiration=1209600000", "--pg.toss.client-key=unused", "--pg.toss.secret-key=unused");
             var before = settings.connect("before"); var candidate = settings.connect("candidate")) {
            var originalRows = fingerprint(before);
            if (!originalRows.equals(fingerprint(candidate))) throw new IllegalStateException("Initial fixture values differ");
            save(settings.output().resolve(finalRun ? "final-fixture-before.json" : coverRun ? "cover-fixture-before.json" : "candidate-fixture-before.json"), originalRows);
            List<Scenario> scenarios = capture(context, before, finalRun);
            save(settings.output().resolve(finalRun ? "final-sql.json" : coverRun ? "cover-sql.json" : "captured-sql.json"), scenarios);
            save(settings.output().resolve("fixture-counts.json"), counts(before));
            var writes = writes(before);
            if (finalRun) {
                var decisions = JSON.readTree(Files.readString(settings.output().resolve("decisions.json")));
                var selected = new ArrayList<String>();
                for (var decision : decisions.path("decisions")) if (decision.path("decision").asText().equals("adopt")) {
                    String family = decision.path("family").asText();
                    selected.add(family);
                    execute(candidate, family.equals("orders-cover") ? coverIndex() : Objects.requireNonNull(CANDIDATES.get(family)));
                }
                execute(candidate, "ANALYZE");
                var results = new LinkedHashMap<>(compare(before, candidate, scenarios, writes));
                results.put("selectedFamilies", selected);
                save(settings.output().resolve("final-combination.json"), results);
            } else if (coverRun) {
                execute(candidate, coverIndex()); execute(candidate, "ANALYZE");
                var results = compare(before, candidate, scenarios.stream().filter(s -> s.family().equals("orders")).toList(), writes);
                save(settings.output().resolve("candidate-orders-cover.json"), results);
                execute(candidate, "DROP INDEX idx_orders_order_date_desc");
            } else {
                for (var entry : CANDIDATES.entrySet()) {
                    execute(candidate, entry.getValue()); execute(candidate, "ANALYZE");
                    var selected = scenarios.stream().filter(s -> s.family().equals(entry.getKey())).toList();
                    var result = compare(before, candidate, selected, writes);
                    save(settings.output().resolve("candidate-" + entry.getKey() + ".json"), result);
                    System.out.println("DEV9_INDEX_CANDIDATE_COMPLETE " + entry.getKey());
                    execute(candidate, "DROP INDEX " + entry.getValue().split(" ")[2]);
                }
            }
            if (!originalRows.equals(fingerprint(before)) || !originalRows.equals(fingerprint(candidate)))
                throw new IllegalStateException("Rolled-back writes changed stored fixture values");
            save(settings.output().resolve(finalRun ? "final-fixture-after.json" : coverRun ? "cover-fixture-after.json" : "candidate-fixture-after.json"), originalRows);
        }
    }
    static String coverIndex() { return "CREATE INDEX idx_orders_order_date_desc ON orders(order_date DESC) INCLUDE(order_id)"; }
    static List<Scenario> capture(org.springframework.context.ConfigurableApplicationContext ctx, Connection c, boolean controls) throws Exception {
        var result = new ArrayList<Scenario>();
        var reviews = ctx.getBean(ReviewService.class); var qna = ctx.getBean(QnaService.class);
        var orders = ctx.getBean(OrderService.class); var products = ctx.getBean(ProductService.class);
        var sales = ctx.getBean(SalesStatisticsService.class); var payments = ctx.getBean(PaymentSalesQueryRepository.class);
        long product = scalar(c, "select min(product_id) from product where status='ON_SALE'");
        for (int size : List.of(10, 50)) {
            var page = PageRequest.of(0, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
            capture(result, "reviews", "review-" + size, () -> reviews.getReviews(product, page));
            capture(result, "qna", "qna-" + size, () -> qna.getProductQnas(product, page));
            for (QnaStatus status : QnaStatus.values()) capture(result, "qna-admin", "qna-admin-" + status + "-" + size, () -> qna.getAdminQnas(status, page));
            capture(result, "qna-admin", "qna-admin-all-" + size, () -> qna.getAdminQnas(null, page));
            if (controls) for (QnaStatus status : Arrays.asList(null, QnaStatus.WAITING, QnaStatus.ANSWERED)) {
                long count = scalar(c, "select count(*) from product_qna" + (status == null ? "" : " where status='" + status.name() + "'"));
                int last = (int) Math.max(0, (count - 1) / size);
                for (int number : new LinkedHashSet<>(List.of(1, last, last + 1)))
                    capture(result, "qna-admin", "qna-admin-" + status + "-" + size + "-page" + number,
                            () -> qna.getAdminQnas(status, PageRequest.of(number, size, page.getSort())));
            }
            capture(result, "orders", "orders-" + size, () -> orders.getAllOrders(PageRequest.of(0, size, Sort.by("id").descending())));
            for (String family : List.of("reviews", "qna", "orders")) {
                long count = scalar(c, family.equals("reviews") ? "select count(*) from reviews where product_id=" + product
                        : family.equals("qna") ? "select count(*) from product_qna where product_id=" + product : "select count(*) from orders");
                int last = (int) Math.max(0, (count - 1) / size);
                for (int number : new LinkedHashSet<>(List.of(1, last, last + 1))) {
                    var adjacent = PageRequest.of(number, size, page.getSort());
                    if (family.equals("reviews")) capture(result, family, "review-" + size + "-page" + number, () -> reviews.getReviews(product, adjacent));
                    else if (family.equals("qna")) capture(result, family, "qna-" + size + "-page" + number, () -> qna.getProductQnas(product, adjacent));
                    else capture(result, family, "orders-" + size + "-page" + number, () -> orders.getAllOrders(PageRequest.of(number, size, Sort.by("id").descending())));
                }
            }
        }
        for (int days : List.of(1, 30, 366)) {
            Instant start = END.minusDays(days - 1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            Instant end = END.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            capture(result, "payment", "payment-day-" + days, () -> payments.aggregatePaidSales(PaymentStatus.SUCCESS, start, end));
            capture(result, "payment", "payment-group-" + days, () -> payments.aggregatePaidSalesByDate("SUCCESS", start, end));
        }
        capture(result, "control", "statistics-read", () -> sales.getSalesStatistics(END.minusDays(29), END, SalesStatisticsUnit.DAILY));
        for (String keyword : Arrays.asList(null, "", "에티오피아", "NO_MATCH_PHASE2", "a", "%", "_", "\\"))
            capture(result, "control", "product-" + keyword, () -> products.getProducts(null, null, null, null, null, null, keyword,
                    PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))));
        if (controls) {
            try (var s = c.createStatement(); var row = s.executeQuery("select p.category_id,p.coffee_profile_id,cp.processing_method_id,cp.bean_type,cp.decaf,p.roast_level from product p join coffee_profile cp using(coffee_profile_id) where p.status='ON_SALE' order by p.product_id limit 1")) {
                row.next(); long category = row.getLong(1), profile = row.getLong(2), method = row.getLong(3);
                var bean = BeanType.valueOf(row.getString(4)); boolean decaf = row.getBoolean(5); var roast = RoastLevel.valueOf(row.getString(6));
                for (int filter = 0; filter < 7; filter++) {
                    int f = filter;
                    capture(result, "control", "product-active-filter-" + f, () -> products.getProducts(f == 0 || f == 6 ? category : null,
                            f == 1 || f == 6 ? profile : null, f == 2 || f == 6 ? method : null, f == 3 || f == 6 ? bean : null,
                            f == 4 || f == 6 ? decaf : null, f == 5 || f == 6 ? roast : null, null, PageRequest.of(0, 10, Sort.by("id"))));
                }
            }
            for (var status : Arrays.asList(null, ProductStatus.HIDDEN, ProductStatus.SOLD_OUT))
                capture(result, "control", "product-admin-" + status, () -> products.getAdminProducts(null, null, null, null, null, null, status, null, PageRequest.of(0, 10, Sort.by("id"))));
        }
        return result;
    }
    static void capture(List<Scenario> result, String family, String name, Supplier<?> work) {
        QueryCapture.start();
        try { work.get(); } finally { result.add(new Scenario(family, name, QueryCapture.stop())); }
        if (result.getLast().queries().isEmpty()) throw new IllegalStateException("No service SQL for " + name);
    }
    static Map<String, Object> compare(Connection a, Connection b, List<Scenario> scenarios, Map<String, String> writes) throws Exception {
        var reads = new ArrayList<Map<String, Object>>();
        for (var scenario : scenarios) {
            if (!digest(a, scenario).equals(digest(b, scenario))) throw new IllegalStateException("Result mismatch " + scenario.name());
            var plans = new ArrayList<Map<String, Object>>();
            for (var q : scenario.queries()) {
                for (String mode : List.of("force_custom_plan", "force_generic_plan"))
                    plans.add(Map.of("mode", mode, "sql", q.sql(), "before", plan(a, q, mode), "candidate", plan(b, q, mode)));
            }
            long[][] samples = sample(a, b, scenario.queries(), null);
            reads.add(Map.of("name", scenario.name(), "family", scenario.family(), "beforeNanos", samples[0],
                    "candidateNanos", samples[1], "plans", plans, "resultsMatch", true));
            System.out.println("DEV9_INDEX_READ " + scenario.name() + " " + Arrays.toString(samples[0]) + " -> " + Arrays.toString(samples[1]));
        }
        var costs = new ArrayList<Map<String, Object>>();
        for (var entry : writes.entrySet()) {
            var q = new QueryCapture.Query(entry.getValue(), Map.of());
            long[][] samples = sample(a, b, List.of(q), entry.getKey());
            costs.add(Map.of("name", entry.getKey(), "beforeNanos", samples[0], "candidateNanos", samples[1],
                    "affectedRowsPerCall", 1, "beforeWal", writePlan(a, entry.getKey(), entry.getValue()), "candidateWal", writePlan(b, entry.getKey(), entry.getValue())));
        }
        return Map.of("scope", "local PostgreSQL actual service SQL replay; 20 warmups then 100 calls x 3; per-batch median; no HTTP claim",
                "reads", reads, "writes", costs, "beforeIndexes", indexes(a), "candidateIndexes", indexes(b));
    }
    static long[][] sample(Connection a, Connection b, List<QueryCapture.Query> queries, String write) throws Exception {
        long[][] result = new long[2][3];
        // EXPLAIN is outside timing. Each prepared statement is reused, including pgjdbc's prepare threshold.
        var first = new ArrayList<PreparedStatement>(); var second = new ArrayList<PreparedStatement>();
        for (var q : queries) { first.add(q.prepare(a, "")); second.add(q.prepare(b, "")); }
        try {
            for (int i = 0; i < 20; i++) { run(a, first, write); run(b, second, write); }
            for (int batch = 0; batch < 3; batch++) {
                long[][] nanos = new long[2][100];
                for (int i = 0; i < 100; i++) {
                    if ((batch + i) % 2 == 0) { nanos[0][i] = run(a, first, write); nanos[1][i] = run(b, second, write); }
                    else { nanos[1][i] = run(b, second, write); nanos[0][i] = run(a, first, write); }
                }
                Arrays.sort(nanos[0]); Arrays.sort(nanos[1]);
                result[0][batch] = (nanos[0][49] + nanos[0][50]) / 2;
                result[1][batch] = (nanos[1][49] + nanos[1][50]) / 2;
            }
        } finally { for (var s : first) s.close(); for (var s : second) s.close(); }
        return result;
    }
    static long run(Connection c, List<PreparedStatement> statements, String write) throws Exception {
        if (write != null) c.setAutoCommit(false);
        if ("payment-insert".equals(write)) execute(c, "delete from payment where order_id=(select min(order_id) from orders)");
        long start = System.nanoTime();
        try {
            for (var s : statements) {
                if (s.execute()) try (var rs = s.getResultSet()) {
                    int cols = rs.getMetaData().getColumnCount();
                    while (rs.next()) for (int i = 1; i <= cols; i++) rs.getObject(i);
                }
                else if (write != null && s.getUpdateCount() != 1) throw new IllegalStateException("Expected exactly one affected row: " + write);
            }
            return System.nanoTime() - start;
        } finally { if (write != null) { c.rollback(); c.setAutoCommit(true); } }
    }
    static JsonNode plan(Connection c, QueryCapture.Query q, String mode) throws Exception {
        execute(c, "SET plan_cache_mode=" + mode);
        var types = new ArrayList<String>(); var literals = new ArrayList<String>();
        try (var s = q.prepare(c, "")) {
            var metadata = s.getParameterMetaData();
            for (int i = 1; i <= metadata.getParameterCount(); i++) {
                String type = metadata.getParameterTypeName(i);
                if (!type.matches("[a-zA-Z0-9_ ]+")) throw new IllegalStateException("Unexpected PostgreSQL type");
                types.add(type);
                Object value = q.bindings().get(i).value();
                if (value instanceof Timestamp timestamp && type.equals("timestamptz")) value = timestamp.toInstant();
                literals.add(value == null ? "NULL" : "'" + value.toString().replace("'", "''") + "'::" + type);
            }
        }
        String sql = q.sql();
        for (int i = 1; i <= types.size(); i++) sql = sql.replaceFirst("\\?", java.util.regex.Matcher.quoteReplacement("$" + i));
        try {
            execute(c, "PREPARE dev9_index_plan" + (types.isEmpty() ? "" : "(" + String.join(",", types) + ")") + " AS " + sql);
            String call = "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) EXECUTE dev9_index_plan" + (types.isEmpty() ? "" : "(" + String.join(",", literals) + ")");
            try (var s = c.createStatement(); var r = s.executeQuery(call)) { r.next(); return JSON.readTree(r.getString(1)); }
        } finally { execute(c, "DEALLOCATE dev9_index_plan"); execute(c, "SET plan_cache_mode=auto"); }
    }
    static JsonNode writePlan(Connection c, String name, String sql) throws Exception {
        c.setAutoCommit(false);
        if (name.equals("payment-insert")) execute(c, "delete from payment where order_id=(select min(order_id) from orders)");
        try (var s = c.createStatement(); var r = s.executeQuery("EXPLAIN (ANALYZE,BUFFERS,WAL,FORMAT JSON) " + sql)) {
            r.next(); return JSON.readTree(r.getString(1));
        } finally { c.rollback(); c.setAutoCommit(true); }
    }
    static String digest(Connection c, Scenario scenario) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var q : scenario.queries()) try (var s = q.prepare(c, ""); var r = s.executeQuery()) {
            while (r.next()) for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                digest.update(Objects.toString(r.getString(i), "<null>").getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static Map<String, String> writes(Connection c) throws Exception {
        long product = scalar(c, "select min(product_id) from product where stock_quantity>0");
        long order = scalar(c, "select min(order_id) from orders");
        long payment = scalar(c, "select min(payment_id) from payment where status='SUCCESS'");
        Map<String, String> writes = new LinkedHashMap<>();
        writes.put("product-insert", "insert into product(product_id,category_id,coffee_profile_id,sku,weight_grams,name,price,stock_quantity,roast_level,status,created_at,updated_at) select 900000001,category_id,coffee_profile_id,'DEV9-8-WRITE',200,'synthetic',1000,10,'MEDIUM','ON_SALE',now(),now() from product where product_id=" + product);
        writes.put("review-insert", "insert into reviews(review_id,member_id,product_id,rating,content,created_at,updated_at) select 900000001,member_id,product_id,5,'synthetic',now(),now() from orders join order_item using(order_id) where not exists(select 1 from reviews r where r.member_id=orders.member_id and r.product_id=order_item.product_id) limit 1");
        writes.put("qna-insert", "insert into product_qna(qna_id,member_id,product_id,title,question,status,created_at,updated_at) select 900000001,member_id,product_id,'synthetic','synthetic','WAITING',now(),now() from orders join order_item using(order_id) limit 1");
        writes.put("order-insert", "insert into orders(order_id,member_id,toss_order_id,order_date,status,used_mileage,delivery_address,product_total_price,delivery_fee,earned_mileage,total_price,created_at,updated_at) select 900000001,member_id,'DEV9-8-WRITE',now(),'PENDING',0,'synthetic',1000,3000,0,4000,now(),now() from orders where order_id=" + order);
        // Delete the existing payment outside timing in the same rolled-back transaction, then measure one real INSERT.
        writes.put("payment-insert", "insert into payment(payment_id,order_id,pg_provider,payment_key,pay_method,status,paid_at) values(900000001," + order + ",'TOSS','DEV9-8-WRITE','card','SUCCESS',now())");
        writes.put("qna-answer", "update product_qna set status='ANSWERED',answer='synthetic',answerer_id=member_id,answered_at=now() where qna_id=(select min(qna_id) from product_qna where status='WAITING')");
        writes.put("product-status", "update product set status='SOLD_OUT' where product_id=" + product);
        writes.put("stock-decrease", "update product set stock_quantity=stock_quantity-1 where product_id=" + product + " and stock_quantity>=1");
        writes.put("order-status", "update orders set status='SHIPPED' where order_id=" + order);
        writes.put("payment-confirm", "update payment set status='SUCCESS',paid_at=now() where payment_id=" + payment);
        writes.put("payment-refund", "update payment set status='REFUNDED',refunded_at=now() where payment_id=" + payment);
        return writes;
    }
    static Object indexes(Connection c) throws Exception {
        return rows(c, "select t.relname as table_name,x.relname as index_name,pg_get_indexdef(i.indexrelid) as definition,indisvalid,indisready,pg_relation_size(i.indexrelid) as bytes from pg_index i join pg_class t on t.oid=i.indrelid join pg_class x on x.oid=i.indexrelid join pg_namespace n on n.oid=t.relnamespace where n.nspname='public' order by t.relname,x.relname");
    }
    static Object counts(Connection c) throws Exception {
        var counts = new LinkedHashMap<String, Long>();
        for (String table : List.of("member", "product", "orders", "order_item", "payment", "reviews", "product_qna")) counts.put(table, scalar(c, "select count(*) from " + table));
        if (counts.get("product") != 192 || counts.get("orders") != 150000 || counts.get("order_item") != 375000 || counts.get("reviews") != 30000 || counts.get("product_qna") != 20000)
            throw new IllegalStateException("Unexpected fixture " + counts);
        return counts;
    }
    static Map<String, String> fingerprint(Connection c) throws Exception {
        var result = new LinkedHashMap<String, String>();
        for (String pair : List.of("member:member_id", "product:product_id", "orders:order_id", "order_item:order_item_id", "payment:payment_id", "reviews:review_id", "product_qna:qna_id")) {
            var parts = pair.split(":");
            try (var s = c.createStatement(); var r = s.executeQuery("select md5(string_agg(md5(to_jsonb(t)::text),'' order by " + parts[1] + ")) from " + parts[0] + " t")) {
                r.next(); result.put(parts[0], r.getString(1));
            }
        }
        return result;
    }
    static List<Map<String, String>> rows(Connection c, String sql) throws Exception {
        var rows = new ArrayList<Map<String, String>>();
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            while (r.next()) { var row = new LinkedHashMap<String, String>(); for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.put(r.getMetaData().getColumnLabel(i), r.getString(i)); rows.add(row); }
        }
        return rows;
    }
    static long scalar(Connection c, String sql) throws Exception { try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); } }
    static void execute(Connection c, String sql) throws Exception { try (var s = c.createStatement()) { s.execute(sql); } }
    static void save(Path path, Object value) throws Exception { Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"); }
}

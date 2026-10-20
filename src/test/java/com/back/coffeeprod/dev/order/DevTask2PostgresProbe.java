package com.back.coffeeprod.dev.order;

import org.flywaydb.core.Flyway;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Real Flyway/JDBC probe for the explicitly owned, disposable DEV2 PostgreSQL only. */
public final class DevTask2PostgresProbe {
    private DevTask2PostgresProbe() {}

    record Settings(String url, String password) {}

    static Settings settings() {
        String url = System.getenv("DEV_TASK2_PG_URL");
        String password = System.getenv("DEV_TASK2_PG_PASSWORD");
        var match = Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:(\\d+)/devtask2_[a-z0-9_]+")
                .matcher(url == null ? "" : url);
        if (!match.matches() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("DEV2 requires a dedicated localhost database and synthetic credentials");
        }
        int port = Integer.parseInt(match.group(1));
        if (port <= 1024 || port > 65535 || port == 5432) {
            throw new IllegalArgumentException("DEV2 must use an isolated non-5432 port");
        }
        UUID.fromString(System.getenv("DEV_TASK2_OWNER"));
        return new Settings(url, password);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected migrate16, migrate18 or snapshot");
        Settings s = settings();
        if (args[0].equals("migrate16") || args[0].equals("migrate18")) {
            String target = args[0].equals("migrate16") ? "16" : "18";
            var flyway = Flyway.configure().dataSource(s.url(), "devtask2", s.password())
                    .locations("classpath:db/migration").target(target)
                    .cleanDisabled(true).baselineOnMigrate(false).validateOnMigrate(true).load();
            var result = flyway.migrate();
            flyway.validate();
            System.out.printf("DEV2_FLYWAY target=%s executed=%d%n", target, result.migrationsExecuted);
        } else if (!args[0].equals("snapshot")) {
            throw new IllegalArgumentException("Unsupported DEV2 probe mode");
        }

        try (Connection connection = DriverManager.getConnection(s.url(), "devtask2", s.password())) {
            try (var statement = connection.createStatement()) { statement.execute("set time zone 'UTC'"); }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("server", rows(connection, "select version(), current_database(), current_user"));
            snapshot.put("history", rows(connection, "select installed_rank, version, description, script, checksum, installed_on, success from flyway_schema_history order by installed_rank"));
            snapshot.put("indexes", rows(connection, """
                    select c.relname, pg_get_indexdef(i.indexrelid), i.indisvalid, i.indisready,
                           pg_index_column_has_property(i.indexrelid,1,'asc') as first_asc,
                           pg_index_column_has_property(i.indexrelid,2,'desc') as second_desc
                    from pg_index i join pg_class c on c.oid=i.indexrelid
                    where c.relname in ('idx_orders_member_order_date_desc','idx_order_item_order_id')
                    order by c.relname
                    """));
            snapshot.put("orders", rows(connection, "select order_id, member_id, toss_order_id, order_date, status, product_total_price, delivery_fee, used_mileage, total_price, delivery_address from orders where toss_order_id like 'DEV2-%' order by order_id"));
            snapshot.put("items", rows(connection, "select oi.* from order_item oi join orders o on o.order_id=oi.order_id where o.toss_order_id like 'DEV2-%' order by oi.order_item_id"));
            snapshot.put("members", rows(connection, "select member_id, mileage from member where email like 'dev2-%@test.invalid' order by member_id"));
            snapshot.put("products", rows(connection, "select product_id, sku, stock_quantity, price from product where sku like 'DEV2-%' order by product_id"));
            System.out.println("DEV2_SNAPSHOT " + JsonMapper.builder().build().writeValueAsString(snapshot));
        }
    }

    private static List<Map<String, String>> rows(Connection connection, String sql) throws Exception {
        var result = new ArrayList<Map<String, String>>();
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
            var metadata = rs.getMetaData();
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) row.put(metadata.getColumnLabel(i), rs.getString(i));
                result.add(row);
            }
        }
        return result;
    }
}

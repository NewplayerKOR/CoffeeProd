package com.back.coffeeprod.dev.indexes;

import org.flywaydb.core.Flyway;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Verifies transactional V19 guards in disposable schemas of the owned functional database. */
public final class DevTask9IndexMigrationProbe {
    private DevTask9IndexMigrationProbe() {}
    public static void main(String[] args) throws Exception {
        var settings = DevTask9IndexProbe.settings();
        String migration;
        try (var in = DevTask9IndexMigrationProbe.class.getResourceAsStream("/db/migration/V19__add_verified_query_indexes.sql")) {
            if (in == null) throw new IllegalStateException("V19 resource missing");
            migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var definitions = new LinkedHashMap<String, String>();
        var matches = Pattern.compile("CREATE INDEX (idx_\\w+)\\s+ON ([^;]+);", Pattern.CASE_INSENSITIVE).matcher(migration);
        while (matches.find()) definitions.put(matches.group(1), matches.group(2));
        if (definitions.isEmpty()) throw new IllegalStateException("No adopted indexes");
        var cases = new ArrayList<Map<String, Object>>();
        var modes = new ArrayList<>(List.of("upgrade", "fresh", "second-ddl-failure"));
        for (String name : definitions.keySet()) {
            modes.add("name:" + name); modes.add("duplicate:" + name);
            modes.add("included:" + name); modes.add("reversed:" + name);
        }
        for (String mode : modes) {
            String schema = "dev9_index_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = settings.connect("functional")) {
                DevTask9IndexProbe.execute(c, "CREATE SCHEMA " + schema);
                try {
                    if (!mode.equals("fresh")) flyway(settings, schema, "18").migrate();
                    if (mode.equals("second-ddl-failure")) {
                        DevTask9IndexProbe.execute(c, "SET search_path TO " + schema);
                        // The first CREATE INDEX succeeds, then the second fails: verify actual transactional DDL rollback.
                        DevTask9IndexProbe.execute(c, "DROP TABLE product_qna");
                    }
                    if (mode.contains(":")) {
                        String name = mode.substring(mode.indexOf(':') + 1);
                        DevTask9IndexProbe.execute(c, "SET search_path TO " + schema);
                        String definition = definitions.get(name);
                        if (mode.startsWith("included:")) definition += " INCLUDE(" + (definition.startsWith("reviews") ? "review_id" : "qna_id") + ")";
                        if (mode.startsWith("reversed:")) definition = definition.replace("DESC", "ASC");
                        DevTask9IndexProbe.execute(c, mode.startsWith("name:") ? "CREATE INDEX " + name + " ON member(name)"
                                : "CREATE INDEX other_name ON " + definition);
                    }
                    var flyway = flyway(settings, schema, null);
                    if (mode.equals("second-ddl-failure") || mode.contains(":") && !mode.startsWith("reversed:")) {
                        boolean rejected = false;
                        try { flyway.migrate(); } catch (org.flywaydb.core.api.FlywayException e) { rejected = true; }
                        if (!rejected) throw new IllegalStateException("Expected migration rejection: " + mode);
                        if (DevTask9IndexProbe.scalar(c, "select count(*) from flyway_schema_history where version='19'") != 0)
                            throw new IllegalStateException("Failed V19 history persisted");
                        long expected = mode.startsWith("name:") ? 1 : 0;
                        String names = String.join(",", definitions.keySet().stream().map(n -> "'" + n + "'").toList());
                        if (DevTask9IndexProbe.scalar(c, "select count(*) from pg_indexes where schemaname='" + schema + "' and indexname in (" + names + ")") != expected)
                            throw new IllegalStateException("Partial V19 indexes persisted");
                    } else {
                        flyway.migrate(); flyway.validate();
                        if (flyway.migrate().migrationsExecuted != 0) throw new IllegalStateException("Migration reran");
                        DevTask9IndexProbe.execute(c, "SET search_path TO " + schema);
                        for (var definition : definitions.entrySet()) {
                            var rows = DevTask9IndexProbe.rows(c, "select pg_get_indexdef(i.indexrelid) as definition,indisvalid,indisready from pg_index i join pg_class x on x.oid=i.indexrelid join pg_namespace n on n.oid=x.relnamespace where n.nspname='" + schema + "' and x.relname='" + definition.getKey() + "'");
                            if (rows.size() != 1 || !rows.getFirst().get("indisvalid").equals("t") || !rows.getFirst().get("indisready").equals("t"))
                                throw new IllegalStateException("Missing or invalid adopted index");
                            String expected = definition.getValue().substring(definition.getValue().indexOf(" USING ")).replaceAll("\\s+", "");
                            String actual = rows.getFirst().get("definition");
                            if (!actual.substring(actual.indexOf(" USING ")).replaceAll("\\s+", "").equals(expected)) throw new IllegalStateException("Wrong index definition");
                        }
                    }
                    cases.add(Map.of("case", mode, "passed", true));
                } finally { DevTask9IndexProbe.execute(c, "SET search_path TO public"); DevTask9IndexProbe.execute(c, "DROP SCHEMA " + schema + " CASCADE"); }
            }
        }
        DevTask9IndexProbe.save(settings.output().resolve("migration-validation.json"), Map.of("cases", cases, "passed", cases.size(), "failed", 0));
        System.out.println("DEV9_INDEX_MIGRATION passed=" + cases.size());
    }
    static Flyway flyway(DevTask9IndexProbe.Settings settings, String schema, String target) {
        var config = Flyway.configure().dataSource(settings.url("functional"), "devtask9", settings.password())
                .schemas(schema).defaultSchema(schema).createSchemas(false).cleanDisabled(true).validateOnMigrate(true);
        if (target != null) config.target(target);
        return config.load();
    }
}

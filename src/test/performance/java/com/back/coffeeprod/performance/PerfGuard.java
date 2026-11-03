package com.back.coffeeprod.performance;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/** Fail closed before Spring/Flyway or any fixture write can open a connection. */
public final class PerfGuard {
    private PerfGuard() {}
    public static void validate(Map<String,String> env) {
        UUID.fromString(env.getOrDefault("PERF_OWNER", ""));
        if (!"true".equals(env.get("PERF_ALLOW_DISPOSABLE"))) throw new IllegalArgumentException("Disposable database acknowledgement required");
        String url = env.getOrDefault("DB_URL", "");
        if (!url.matches("jdbc:postgresql://(db:5432|127\\.0\\.0\\.1:[0-9]{4,5})/coffeeprod_perf_phase2")
                || !"perf_phase2".equals(env.get("DB_USERNAME"))) throw new IllegalArgumentException("Dedicated phase2 database required");
        if (url.contains("127.0.0.1:5432/")) throw new IllegalArgumentException("Shared PostgreSQL port forbidden");
        if (!"http://mock-pg:9080".equals(env.get("PERF_PG_URL")) && !env.getOrDefault("PERF_PG_URL", "").matches("http://127\\.0\\.0\\.1:[0-9]{4,5}"))
            throw new IllegalArgumentException("Dedicated HTTP mock PG required");
        for (String key : new String[]{"PERF_SOURCE_HASH", "PERF_MEASUREMENT_HASH"})
            if (!env.getOrDefault(key, "").matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Missing source identity");
        if (env.containsKey("PERF_COMPILED_SOURCE_HASH") && !env.get("PERF_SOURCE_HASH").equals(env.get("PERF_COMPILED_SOURCE_HASH")))
            throw new IllegalArgumentException("Image and requested source identity differ");
    }
    public static void validateRunId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid run ID");
    }
}

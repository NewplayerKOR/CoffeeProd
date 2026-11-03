package com.back.coffeeprod.performance;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.lang.management.ManagementFactory;

/** Existing /api/v1/admin/** security applies. This class is absent from release jars. */
@RestController @Profile("perf")
@RequestMapping("/api/v1/admin/performance")
public class PerfControl {
    private final String instanceId = UUID.randomUUID().toString();
    private final String processStartedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).toString();
    @GetMapping("/manifest") public Map<String,Object> manifest() {
        return Map.of("sourceHash", System.getenv().getOrDefault("PERF_SOURCE_HASH",""),
                "measurementHash", System.getenv().getOrDefault("PERF_MEASUREMENT_HASH",""),
                "owner", System.getenv().getOrDefault("PERF_OWNER",""),
                "diagnostic", Boolean.parseBoolean(System.getenv("PERF_DIAGNOSTIC")),
                "disposable", true,
                "protocolVersion", 2,
                "instanceId", instanceId,
                "processStartedAt", processStartedAt);
    }
}

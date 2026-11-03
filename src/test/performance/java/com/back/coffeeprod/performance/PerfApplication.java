package com.back.coffeeprod.performance;

import com.back.coffeeprod.CoffeeprodApplication;
import org.springframework.boot.SpringApplication;
import java.util.*;

public final class PerfApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("mock-pg")) { PerfPgServer.main(Arrays.copyOfRange(args,1,args.length)); return; }
        if (args.length==2 && args[0].equals("dependencies")) {
            var hashes=new TreeMap<String,String>();
            try(var jar=new java.util.jar.JarFile(args[1])) {
                for(var entry:jar.stream().filter(e->e.getName().startsWith("BOOT-INF/lib/")&&e.getName().endsWith(".jar")).toList())
                    try(var stream=jar.getInputStream(entry)) {
                        var digest=java.security.MessageDigest.getInstance("SHA-256");stream.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest));
                        hashes.put(entry.getName(),java.util.HexFormat.of().formatHex(digest.digest()));
                    }
            }
            System.out.println(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(hashes));return;
        }
        PerfGuard.validate(System.getenv());
        if (args.length>0 && args[0].equals("migrate")) {
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false).load();
            flyway.migrate();flyway.validate();return;
        }
        List<String> parameters = new ArrayList<>(List.of(args));
        parameters.add("--spring.profiles.active=prod,perf");
        parameters.add("--pg.toss.base-url=" + System.getenv("PERF_PG_URL"));
        parameters.add("--pg.toss.secret-key=perf-phase2-synthetic");
        if (Boolean.parseBoolean(System.getenv("PERF_DIAGNOSTIC"))) {
            parameters.add("--spring.jpa.properties.hibernate.generate_statistics=true");
            parameters.add("--spring.jpa.properties.hibernate.session_factory.statement_inspector=" + SqlCapture.class.getName());
        }
        SpringApplication.run(CoffeeprodApplication.class, parameters.toArray(String[]::new));
    }
}

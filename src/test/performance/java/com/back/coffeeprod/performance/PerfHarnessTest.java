package com.back.coffeeprod.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.back.coffeeprod.domain.payment.gateway.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PerfHarnessTest {
    static Map<String,String> env() {
        return new HashMap<>(Map.of("PERF_OWNER",UUID.randomUUID().toString(),"PERF_ALLOW_DISPOSABLE","true",
            "DB_URL","jdbc:postgresql://db:5432/coffeeprod_perf_phase2","DB_USERNAME","perf_phase2",
            "PERF_PG_URL","http://mock-pg:9080","PERF_SOURCE_HASH","a".repeat(64),"PERF_MEASUREMENT_HASH","b".repeat(64)));
    }
    @Test void acceptsOnlyOwnedSettings() { assertDoesNotThrow(()->PerfGuard.validate(env())); }
    @ParameterizedTest @ValueSource(strings={"jdbc:postgresql://db:5432/coffee_db","jdbc:postgresql://192.168.0.8:5432/coffeeprod_perf_phase2","jdbc:postgresql://127.0.0.1:5432/coffeeprod_perf_phase2"})
    void refusesSharedDatabase(String url) { var e=env();e.put("DB_URL",url);assertThrows(IllegalArgumentException.class,()->PerfGuard.validate(e)); }
    @ParameterizedTest @ValueSource(strings={"PERF_OWNER","PERF_ALLOW_DISPOSABLE","PERF_SOURCE_HASH","PERF_MEASUREMENT_HASH"})
    void refusesMissingIdentity(String key) {var e=env();e.remove(key);assertThrows(IllegalArgumentException.class,()->PerfGuard.validate(e));}
    @Test void refusesRealProvider() { var e=env();e.put("PERF_PG_URL","https://api.tosspayments.com");assertThrows(IllegalArgumentException.class,()->PerfGuard.validate(e)); }
    @Test void refusesUnsafeResultPaths() { for(String id:List.of("../other","/tmp","a b","a;rm",""))assertThrows(IllegalArgumentException.class,()->PerfGuard.validateRunId(id)); }
    @Test void mockHasRealLookupAndIdempotentFullCancel() throws Exception {
        try(var pg=new PerfPgServer(0)) {
            var g=new TossPaymentGateway("perf", "http://127.0.0.1:"+pg.port(),new PaymentRecoveryProperties(Duration.ofSeconds(1),Duration.ofSeconds(3),Duration.ofSeconds(5)));
            var approved=g.confirm("perf-key","perf-order",12300,"confirm-1");
            assertTrue(approved.approved()); assertEquals(approved,g.confirm("perf-key","perf-order",12300,"confirm-1"));
            assertEquals(approved,g.lookupByOrderId("perf-order"));assertEquals(approved,g.lookupByPaymentKey("perf-key"));
            var cancel=g.cancelFull("perf-key","test","cancel-1");assertNotNull(cancel.fullCancellation());
            assertEquals(cancel,g.cancelFull("perf-key","test","cancel-1"));assertEquals(2,pg.posts());assertEquals(4,pg.postCalls());assertEquals(2,pg.lookups());
        }
    }
    @Test void mockDelayAndMissingLookupAreObservable() throws Exception {
        try(var pg=new PerfPgServer(0)) {
            assertThrows(IllegalArgumentException.class,()->pg.delay(45001));pg.delay(100);
            long start=System.nanoTime();var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+pg.port()+"/v1/payments/absent")).build(),HttpResponse.BodyHandlers.discarding());
            assertEquals(404,r.statusCode());assertTrue(System.nanoTime()-start>=90_000_000);assertEquals(1,pg.lookups());pg.reset();assertEquals(0,pg.lookups());
        }
    }
    @Test void captureStoresTemplatesOnlyAndCanReset() {SqlCapture.reset();new SqlCapture().inspect("select * from member where email=?");assertEquals(List.of("select * from member where email=?"),SqlCapture.templates());SqlCapture.reset();assertTrue(SqlCapture.templates().isEmpty());}
    @Test void jdbcArraysAreCopiedToSerializableValues() throws Exception {
        var array=mock(java.sql.Array.class);when(array.getBaseTypeName()).thenReturn("int8");when(array.getArray()).thenReturn(new Long[]{1L,2L});
        Object copied=JdbcCapture.copyBinding(array);assertEquals(new JdbcCapture.ArrayValue("int8",List.of(1L,2L)),copied);
        String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(copied);
        assertFalse(json.contains("Connection"));assertTrue(json.contains("int8"));
    }
    @Test void lifecycleIdentityIsStableWithinOneAppInstance() {
        var control=new PerfControl();var first=control.manifest();var second=control.manifest();
        assertEquals(first.get("instanceId"),second.get("instanceId"));
        assertEquals(first.get("processStartedAt"),second.get("processStartedAt"));
        assertEquals(2,first.get("protocolVersion"));
        assertNotNull(java.time.Instant.parse((String)first.get("processStartedAt")));
    }
    @Test void appInstancesHaveDifferentLifecycleIdentities() {
        assertNotEquals(new PerfControl().manifest().get("instanceId"),new PerfControl().manifest().get("instanceId"));
    }
}

package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.CoffeeprodApplication;
import com.back.coffeeprod.domain.payment.gateway.*;
import com.back.coffeeprod.domain.payment.entity.*;
import com.back.coffeeprod.domain.order.entity.OrderStatus;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

/** Executed in a real separate JVM. Never uses the real PG or production database. */
public final class QaRestartChild {
    @TestConfiguration public static class Config {
        @Bean @Primary PaymentGateway restartGateway() {
            var delegate=new TossPaymentGateway("qa-synthetic",System.getenv("QA_TASK6_STUB_URL"),
                    new PaymentRecoveryProperties(Duration.ofMillis(150),Duration.ofSeconds(2),Duration.ofSeconds(4)));
            return new PaymentGateway() {
                void pause(String target) {
                    if(!System.getenv("QA_TASK6_RESTART_MODE").equals("crash-"+target))return;
                    try {Files.writeString(Path.of(System.getenv("QA_TASK6_MARKER")),"PG_SUCCESS_BEFORE_LOCAL_APPLY");new CountDownLatch(1).await();}
                    catch(Exception e){throw new IllegalStateException(e);}
                }
                public GatewayPayment confirm(String k,String o,int a,String i){var p=delegate.confirm(k,o,a,i);pause("confirm");return p;}
                public GatewayPayment lookupByPaymentKey(String k){return delegate.lookupByPaymentKey(k);}
                public GatewayPayment lookupByOrderId(String o){return delegate.lookupByOrderId(o);}
                public GatewayPayment cancelFull(String k,String r,String i){var p=delegate.cancelFull(k,r,i);pause("refund");return p;}
            };
        }
    }
    public static void main(String[] args) {
        try {run();System.exit(0);}
        catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
    static void run() throws Exception {
        String mode=System.getenv("QA_TASK6_RESTART_MODE"),url=System.getenv("QA_TASK6_RESTART_JDBC");
        if(url==null||!(url.startsWith("jdbc:h2:file:")||url.equals("jdbc:postgresql://127.0.0.1:55486/qa6_restart")))throw new IllegalArgumentException("Dedicated QA DB required");
        try(var context=open(url,url.startsWith("jdbc:postgresql:")||mode.equals("recover")?"validate":"create",Config.class)) {
            var support=new QaPaymentSupport(){};context.getAutowireCapableBeanFactory().autowireBean(support);
            Path state=Path.of(System.getenv("QA_TASK6_STATE"));
            if(!mode.equals("recover")) {
                var f=support.fixture();Files.writeString(state,QaPaymentSupport.JSON.writeValueAsString(f));
                support.attempt(f);
                if(mode.equals("crash-refund"))support.workflow.cancel(f.member(),f.order(),false);
                throw new AssertionError("Child must pause before apply");
            }
            var f=QaPaymentSupport.JSON.readValue(Files.readString(state),QaPaymentSupport.Fixture.class);
            boolean refund="refund".equals(System.getenv("QA_TASK6_TARGET"));
            var type=refund?PaymentOperation.Type.CANCEL_FULL:PaymentOperation.Type.CONFIRM;
            var op=support.operation(f,type);assertEquals(PaymentOperation.Status.IN_FLIGHT,op.getStatus());
            support.resources(f,9,refund?797:700,0);assertEquals(refund?OrderStatus.PAID:OrderStatus.PENDING,support.orders.findById(f.order()).orElseThrow().getStatus());
            if(!refund)assertTrue(support.payments.findByOrdersId(f.order()).isEmpty());
            support.expire(op.getId());support.workflow.recoverDue();
            assertEquals(PaymentOperation.Status.SUCCEEDED,support.operations.findById(op.getId()).orElseThrow().getStatus());
            support.resources(f,refund?10:9,refund?1000:797,0);
            assertEquals(refund?PaymentStatus.REFUNDED:PaymentStatus.SUCCESS,support.payments.findByOrdersId(f.order()).orElseThrow().getStatus());
            support.workflow.recoverDue();assertEquals(200,support.workflow.reconcile(f.order()).status());
            support.resources(f,refund?10:9,refund?1000:797,0);
            Files.writeString(state.resolveSibling("recovery-result.json"),"{\"result\":\"PASS\",\"realJvmRestart\":true,\"target\":\""+(refund?"refund":"confirm")+"\"}");
        }
    }
    static org.springframework.context.ConfigurableApplicationContext open(String url,String ddl,Class<?> configuration) {
        boolean pg=url.startsWith("jdbc:postgresql:");
        Map<String,Object> p=new LinkedHashMap<>();
        p.put("spring.profiles.active","test");p.put("spring.datasource.url",url);
        p.put("spring.datasource.driver-class-name",pg?"org.postgresql.Driver":"org.h2.Driver");p.put("spring.datasource.username",pg?"qa6":"sa");
        p.put("spring.datasource.password",pg?System.getenv("QA_TASK6_PG_PASSWORD"):"");
        p.put("spring.jpa.properties.hibernate.dialect",pg?"org.hibernate.dialect.PostgreSQLDialect":"org.hibernate.dialect.H2Dialect");
        p.put("spring.jpa.hibernate.ddl-auto",ddl);p.put("spring.flyway.enabled",pg);
        p.put("server.port",0);p.put("server.address","127.0.0.1");p.put("management.server.port",0);p.put("management.server.address","127.0.0.1");p.put("spring.jpa.show-sql",false);p.put("logging.level.root","ERROR");
        p.put("spring.data.redis.host","localhost");p.put("spring.data.redis.port",6379);p.put("spring.data.redis.password","qa-unused");
        p.put("jwt.secret-key","qa-task6-synthetic-secret-key-minimum-32-bytes");p.put("jwt.access-expiration",1800000);p.put("jwt.refresh-expiration",1209600000);
        p.put("pg.toss.client-key","qa-unused");p.put("pg.toss.secret-key","qa-unused");p.put("app.payment.workers-enabled",false);
        var application=new SpringApplication(CoffeeprodApplication.class,configuration);
        // Command-line values override application-test.yml without changing production files.
        String[] properties=p.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new);
        return application.run(properties);
    }
}

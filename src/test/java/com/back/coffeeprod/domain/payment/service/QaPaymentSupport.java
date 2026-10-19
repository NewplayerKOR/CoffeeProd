package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.entity.*;
import com.back.coffeeprod.domain.cart.repository.*;
import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.entity.*;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.back.coffeeprod.domain.payment.entity.*;
import com.back.coffeeprod.domain.payment.gateway.*;
import com.back.coffeeprod.domain.payment.repository.*;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.product.repository.*;
import com.back.coffeeprod.domain.statistics.repository.*;
import com.back.coffeeprod.domain.statistics.service.*;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(QaPaymentSupport.Config.class)
@TestPropertySource(properties={"spring.data.redis.host=localhost","spring.data.redis.port=6379",
        "spring.data.redis.password=qa-unused", "jwt.secret-key=qa-task6-synthetic-secret-key-minimum-32-bytes",
        "jwt.access-expiration=1800000","jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=qa-unused","pg.toss.secret-key=qa-unused","spring.jpa.show-sql=false"})
abstract class QaPaymentSupport {
    static final QaPgServer PG=new QaPgServer();
    static final JsonMapper JSON=JsonMapper.builder().build();
    static boolean postgres() {return System.getenv("QA_TASK6_PG_URL")!=null;}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("QA_TASK6_PG_URL");
        r.add("spring.datasource.url",()->postgres()?url:"jdbc:h2:mem:qa6;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        r.add("spring.datasource.driver-class-name",()->postgres()?"org.postgresql.Driver":"org.h2.Driver");
        r.add("spring.datasource.username",()->postgres()?"qa6":"sa");
        r.add("spring.datasource.password",()->postgres()?System.getenv("QA_TASK6_PG_PASSWORD"):"");
        r.add("spring.jpa.properties.hibernate.dialect",()->postgres()?"org.hibernate.dialect.PostgreSQLDialect":"org.hibernate.dialect.H2Dialect");
        r.add("spring.jpa.hibernate.ddl-auto",()->postgres()?"validate":"create-drop");
        r.add("spring.flyway.enabled",QaPaymentSupport::postgres);
    }
    @TestConfiguration static class Config {
        @Bean @Primary PaymentGateway qaGateway() {
            return new TossPaymentGateway("qa-synthetic",PG.url(),new PaymentRecoveryProperties(Duration.ofMillis(150),Duration.ofMillis(650),Duration.ofSeconds(2)));
        }
    }
    @Autowired PaymentWorkflow workflow;
    @Autowired PaymentTransactions transactions;
    @Autowired OrderService orderService;
    @Autowired MemberRepository members;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired com.back.coffeeprod.domain.product.service.ProductService productService;
    @Autowired AddressRepository addresses;
    @Autowired CartRepository carts;
    @Autowired CartItemRepository items;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired PaymentOperationRepository operations;
    @Autowired SalesRebuildRequestRepository rebuilds;
    @Autowired SalesStatisticsRepository statistics;
    @Autowired SalesRebuildService rebuildService;
    @Autowired SalesStatisticsService sales;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired JwtUtil jwt;
    @LocalServerPort int port;
    @BeforeEach void resetProvider() {PG.reset();}
    record Fixture(Long member, Long product, Long order, String toss, int amount) {}
    Fixture fixture() {return fixture(null,300);}
    Fixture fixture(Long existingMember,int used) {
        String id=UUID.randomUUID().toString();
        Member member;
        if(existingMember==null) {
            member=Member.builder().email(id+"@test.invalid").password("qa").name("QA").nickname(id).role(Role.USER).build();
            member.addMileage(1000);members.saveAndFlush(member);
        } else member=members.findById(existingMember).orElseThrow();
        var address=addresses.saveAndFlush(Address.builder().member(member).recipient("QA").phone("01000000000")
                .zipcode("12345").addressLine1("QA").addressLine2("").isDefault(true).build());
        var category=categories.saveAndFlush(Category.builder().name(id).build());
        var product=products.saveAndFlush(Product.builder().category(category).sku(id).weightGrams(200).name("QA")
                .price(10000).stockQuantity(10).roastLevel(RoastLevel.MEDIUM).description("QA").build());
        var cart=carts.findByMemberId(member.getId()).orElseGet(()->carts.saveAndFlush(Cart.builder().member(member).build()));
        items.saveAndFlush(CartItem.builder().cart(cart).product(product).quantity(1).grindType(GrindType.WHOLE_BEAN).build());
        var request=new OrderDto.CreateRequest();
        ReflectionTestUtils.setField(request,"addressId",address.getId());ReflectionTestUtils.setField(request,"usedMileage",used);
        var order=orderService.createOrder(member.getId(),request);
        return new Fixture(member.getId(),product.getId(),order.getOrderId(),order.getTossOrderId(),order.getTotalPrice());
    }
    PaymentDto.ConfirmRequest request(Fixture f) {
        var r=new PaymentDto.ConfirmRequest();ReflectionTestUtils.setField(r,"paymentKey","qa-"+f.toss());
        ReflectionTestUtils.setField(r,"tossOrderId",f.toss());ReflectionTestUtils.setField(r,"amount",f.amount());return r;
    }
    PaymentWorkflow.Result attempt(Fixture f) {
        ready(f);
        return workflow.confirm(f.member(),request(f));
    }
    void ready(Fixture f) {
        // Fault-injection cases start from a committed, explicitly due READY operation.
        // Do not assume the database's timestamp precision permits an immediate first claim.
        var prepared=transactions.prepareConfirm(f.member(),request(f));
        if(operations.findById(prepared.operationId()).orElseThrow().getStatus()==PaymentOperation.Status.READY)
            jdbc.update("update payment_operation set next_attempt_at=? where id=? and status='READY'",java.sql.Timestamp.from(Instant.now().minusSeconds(2)),prepared.operationId());
    }
    PaymentOperation operation(Fixture f,PaymentOperation.Type type) {
        return operations.findAll().stream().filter(p->p.getOrders().getId().equals(f.order())&&p.getType()==type).findFirst().orElseThrow();
    }
    void resources(Fixture f,int stock,int mileage,int due) {
        assertEquals(stock,products.findById(f.product()).orElseThrow().getStockQuantity(),"재고");
        var member=members.findById(f.member()).orElseThrow();assertEquals(mileage,member.getMileage(),"사용 가능 마일리지");assertEquals(due,member.getMileageRecoveryDue(),"회수 대기액");
    }
    void tx(Runnable action) {new TransactionTemplate(manager).executeWithoutResult(s->action.run());}
    void expire(Long id) {jdbc.update("update payment_operation set lease_until=?, next_attempt_at=? where id=?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(2)),java.sql.Timestamp.from(Instant.now().minusSeconds(2)),id);}
    String token(Long member) {return jwt.generateAccessToken(member,Role.USER);}
    String admin() {
        String id=UUID.randomUUID().toString();var m=members.saveAndFlush(Member.builder().email(id+"@test.invalid").password("qa")
                .name("QA").nickname(id).role(Role.ADMIN).build());return jwt.generateAccessToken(m.getId(),Role.ADMIN);
    }
    HttpResponse<String> http(String verb,String path,String body,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(b.method(verb,HttpRequest.BodyPublishers.ofString(body==null?"":body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response,int status) {
        assertEquals(status,response.statusCode(),response.body());var root=JSON.readTree(response.body());assertEquals(status,root.path("status").asInt());return root.path("data");
    }
    String cancelPath(Fixture f) {return "/api/v1/orders/"+f.order()+"/cancel";}
    void together(List<Runnable> actions) throws Exception {
        var ready=new CountDownLatch(actions.size());var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=actions.stream().map(a->pool.submit(()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));a.run();return null;})).toList();
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();for(var task:tasks)task.get(20,TimeUnit.SECONDS);
        } finally {start.countDown();}
    }
}

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
import com.back.coffeeprod.global.exception.*;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PaymentWorkflowIntegrationTest.Config.class)
@TestPropertySource(properties = {"spring.data.redis.host=localhost","spring.data.redis.port=6379",
        "spring.data.redis.password=test", "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000","jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key","pg.toss.secret-key=test-secret-key", "spring.jpa.show-sql=false"})
class PaymentWorkflowIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("DEV_TASK4_PG_URL");
        boolean pg = url != null;
        r.add("spring.datasource.url", () -> pg ? url : "jdbc:h2:mem:workflow;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        r.add("spring.datasource.driver-class-name", () -> pg ? "org.postgresql.Driver" : "org.h2.Driver");
        r.add("spring.datasource.username", () -> pg ? "devtask4" : "sa");
        r.add("spring.datasource.password", () -> pg ? "devtask4-isolated" : "");
        r.add("spring.jpa.properties.hibernate.dialect", () -> pg ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
        r.add("spring.jpa.hibernate.ddl-auto", () -> pg ? "validate" : "create-drop");
        r.add("spring.flyway.enabled", () -> pg);
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
    @Autowired FaultGateway gateway;
    @Autowired JwtUtil jwt;
    @LocalServerPort int port;
    @BeforeEach void resetGateway() { gateway.reset(); }
    record Fixture(Long member, Long product, Long order, String toss, int amount) {}
    Fixture fixture() { return fixture(300); }
    Fixture fixture(int used) {
        String id = UUID.randomUUID().toString();
        var member = Member.builder().email(id+"@test.invalid").password("fixture").name("fixture").nickname(id).role(Role.USER).build();
        member.addMileage(1000); members.saveAndFlush(member);
        var address = addresses.saveAndFlush(Address.builder().member(member).recipient("fixture").phone("01000000000")
                .zipcode("12345").addressLine1("fixture").addressLine2("").isDefault(true).build());
        var category = categories.saveAndFlush(Category.builder().name(id).build());
        var product = products.saveAndFlush(Product.builder().category(category).sku(id).weightGrams(200).name("fixture")
                .price(10000).stockQuantity(10).roastLevel(RoastLevel.MEDIUM).description("fixture").build());
        var cart = carts.saveAndFlush(Cart.builder().member(member).build());
        items.saveAndFlush(CartItem.builder().cart(cart).product(product).quantity(1).grindType(GrindType.WHOLE_BEAN).build());
        var request = new OrderDto.CreateRequest();
        ReflectionTestUtils.setField(request,"addressId",address.getId()); ReflectionTestUtils.setField(request,"usedMileage",used);
        var order = orderService.createOrder(member.getId(),request);
        return new Fixture(member.getId(),product.getId(),order.getOrderId(),order.getTossOrderId(),order.getTotalPrice());
    }
    PaymentDto.ConfirmRequest confirm(Fixture f) {
        var r = new PaymentDto.ConfirmRequest();
        ReflectionTestUtils.setField(r,"paymentKey","key-"+f.toss());
        ReflectionTestUtils.setField(r,"tossOrderId",f.toss()); ReflectionTestUtils.setField(r,"amount",f.amount()); return r;
    }
    PaymentOperation operation(Fixture f, PaymentOperation.Type type) {
        return operations.findAll().stream().filter(p -> p.getOrders().getId().equals(f.order()) && p.getType() == type).findFirst().orElseThrow();
    }
    void resources(Fixture f, int stock, int mileage, int due) {
        assertEquals(stock,products.findById(f.product()).orElseThrow().getStockQuantity());
        var m=members.findById(f.member()).orElseThrow(); assertEquals(mileage,m.getMileage()); assertEquals(due,m.getMileageRecoveryDue());
    }
    void expire(Long id) { jdbc.update("update payment_operation set lease_until = ?, next_attempt_at = ? where id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(1)),java.sql.Timestamp.from(Instant.now().minusSeconds(1)),id); }
    void tx(Runnable r) { new TransactionTemplate(manager).executeWithoutResult(s -> r.run()); }

    @Test void pendingUserAndAdminCancellationIsExactlyOnceWithoutPg() {
        for(boolean admin: List.of(false,true)) {
            var f=fixture(); assertEquals(200,workflow.cancel(f.member(),f.order(),admin).status());
            assertEquals(200,workflow.cancel(f.member(),f.order(),admin).status()); resources(f,10,1000,0);
        }
        assertEquals(0,gateway.confirmCalls.get()); assertEquals(0,gateway.cancelCalls.get());
    }
    @Test void paidUserAndAdminFullCancellationAndDuplicateApprovalAreIdempotent() {
        for(boolean admin: List.of(false,true)) {
            var f=fixture(); assertEquals(200,workflow.confirm(f.member(),confirm(f)).status());
            assertEquals(200,workflow.confirm(f.member(),confirm(f)).status()); resources(f,9,797,0);
            assertEquals(200,workflow.cancel(f.member(),f.order(),admin).status());
            assertEquals(200,workflow.cancel(f.member(),f.order(),admin).status()); resources(f,10,1000,0);
            var p=payments.findByOrdersId(f.order()).orElseThrow(); assertEquals(PaymentStatus.REFUNDED,p.getStatus());
            assertNotNull(p.getRefundedAt()); assertNotNull(operation(f,PaymentOperation.Type.CANCEL_FULL).getPgTransactionKey());
        }
        assertEquals(2,gateway.confirmCalls.get()); assertEquals(2,gateway.cancelCalls.get());
    }
    @Test void lostApprovalResponsePreservesResourcesThenLookupConverges() {
        var f=fixture(); gateway.loseConfirm=true;
        assertEquals(202,workflow.confirm(f.member(),confirm(f)).status()); resources(f,9,700,0);
        assertEquals(OrderStatus.PENDING,orders.findById(f.order()).orElseThrow().getStatus());
        assertEquals(200,workflow.reconcile(f.order()).status()); resources(f,9,797,0);
        assertEquals(1,gateway.confirmCalls.get()); assertEquals(1,payments.findAll().stream().filter(p -> p.getOrders().getId().equals(f.order())).count());
    }
    @Test void lostRefundResponseDoesNotRestoreUntilLookupAndCannotShip() {
        var f=fixture(); workflow.confirm(f.member(),confirm(f)); gateway.loseCancel=true;
        assertEquals(202,workflow.cancel(f.member(),f.order(),false).status()); resources(f,9,797,0);
        var req=new OrderDto.StatusUpdateRequest(); ReflectionTestUtils.setField(req,"status",OrderStatus.SHIPPED); ReflectionTestUtils.setField(req,"trackingNo","tracking");
        assertEquals(ErrorCode.PAYMENT_OPERATION_CONFLICT,assertThrows(CustomException.class,()->orderService.updateOrderStatus(f.order(),req)).getErrorCode());
        assertEquals(200,workflow.reconcile(f.order()).status()); resources(f,10,1000,0); assertEquals(1,gateway.cancelCalls.get());
    }
    @Test void finalApprovalAndRefundRejectionsHaveDifferentCompensation() {
        var f=fixture(); gateway.rejectConfirm=true;
        assertEquals(ErrorCode.PAYMENT_CONFIRM_REJECTED,assertThrows(CustomException.class,()->workflow.confirm(f.member(),confirm(f))).getErrorCode());
        resources(f,10,1000,0); assertEquals(200,workflow.cancel(f.member(),f.order(),false).status()); gateway.rejectConfirm=false;
        var paid=fixture(); workflow.confirm(paid.member(),confirm(paid)); gateway.rejectCancel=true;
        for(int i=0;i<2;i++) assertEquals(ErrorCode.PAYMENT_CANCEL_REJECTED,
                assertThrows(CustomException.class,()->workflow.cancel(paid.member(),paid.order(),false)).getErrorCode());
        resources(paid,9,797,0); assertFalse(orders.findById(paid.order()).orElseThrow().isCancelRequested()); assertEquals(1,gateway.cancelCalls.get());
    }
    @Test void cancellationDuringApprovalPreservesOriginalAdminActorAndSerializesRefund() throws Exception {
        var f=fixture(); gateway.block=true;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var approval=executor.submit(()->workflow.confirm(f.member(),confirm(f)));
            assertTrue(gateway.entered.await(5,TimeUnit.SECONDS));
            assertEquals(202,workflow.cancel(999L,f.order(),true).status()); resources(f,9,700,0);
            gateway.release.countDown(); assertEquals(200,approval.get(10,TimeUnit.SECONDS).status());
        } finally { gateway.release.countDown(); }
        resources(f,10,1000,0); assertEquals("관리자 주문 전체 취소",operation(f,PaymentOperation.Type.CANCEL_FULL).getReason());
    }
    @Test void commitFailureAfterPgSuccessRollsBackAndDurableLeaseRecovers() {
        var f=fixture(); tx(()-> {var m=members.findLockedById(f.member()).orElseThrow();m.addMileage(Integer.MAX_VALUE-700);});
        assertEquals(202,workflow.confirm(f.member(),confirm(f)).status());
        assertTrue(payments.findByOrdersId(f.order()).isEmpty()); assertEquals(OrderStatus.PENDING,orders.findById(f.order()).orElseThrow().getStatus());
        tx(()->members.findLockedById(f.member()).orElseThrow().useMileage(Integer.MAX_VALUE-700));
        expire(operation(f,PaymentOperation.Type.CONFIRM).getId());
        assertEquals(200,workflow.reconcile(f.order()).status()); resources(f,9,797,0); assertEquals(1,gateway.confirmCalls.get());
    }
    @Test void staleLeaseCannotOverwriteNewOwnerAndUnknownNeverMeansSuccess() {
        var f=fixture(); var prepared=transactions.prepareConfirm(f.member(),confirm(f));
        var stale=transactions.claim(prepared.operationId()); assertNotNull(stale); assertNull(transactions.claim(prepared.operationId()));
        expire(prepared.operationId()); var current=transactions.claim(prepared.operationId()); assertNotNull(current);
        var pg=gateway.confirm(stale.paymentKey(),stale.tossOrderId(),stale.amount(),stale.idempotencyKey());
        transactions.apply(stale,pg,false,false,null); assertTrue(payments.findByOrdersId(f.order()).isEmpty());
        transactions.apply(current,pg,false,false,null); resources(f,9,797,0);
        transactions.apply(stale,null,true,false,null); resources(f,9,797,0);
    }
    @Test void exhaustedAndExpiredOperationsOnlyLookupAndNeverPostAgain() {
        for(boolean expired: List.of(false,true)) {
            var f=fixture(); gateway.unknown=true; assertEquals(202,workflow.confirm(f.member(),confirm(f)).status()); gateway.unknown=false;
            Long op=operation(f,PaymentOperation.Type.CONFIRM).getId();
            if(expired) jdbc.update("update payment_operation set first_sent_at = ? where id = ?",java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(16))),op);
            else jdbc.update("update payment_operation set attempt_count = 10 where id = ?",op);
            assertEquals(ErrorCode.PAYMENT_OPERATION_CONFLICT,assertThrows(CustomException.class,()->workflow.reconcile(f.order())).getErrorCode());
            assertEquals(PaymentOperation.Status.MANUAL_REQUIRED,operations.findById(op).orElseThrow().getStatus()); resources(f,9,700,0);
        }
        assertEquals(2,gateway.confirmCalls.get());
    }
    @Test void spentRewardsBecomeRecoveryDueAndFutureCreditsSettleItFirst() {
        var f=fixture(0); workflow.confirm(f.member(),confirm(f));
        tx(()->members.findLockedById(f.member()).orElseThrow().useMileage(1100));
        workflow.cancel(f.member(),f.order(),false); resources(f,10,0,100);
        tx(()->members.findLockedById(f.member()).orElseThrow().addMileage(30)); resources(f,10,0,70);
        tx(()->members.findLockedById(f.member()).orElseThrow().addMileage(100)); resources(f,10,30,0);
    }

    @Test void concurrentRefundsAndCreditsPreserveStockAndBalance() throws Exception {
        var f=fixture(0); workflow.confirm(f.member(),confirm(f));
        tx(()->members.findLockedById(f.member()).orElseThrow().useMileage(1100));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<8;i++) tasks.add(pool.submit(()-> { start.await(); workflow.cancel(f.member(),f.order(),false); return null; }));
            tasks.add(pool.submit(()-> { start.await(); tx(()->members.findLockedById(f.member()).orElseThrow().addMileage(150)); return null; }));
            start.countDown(); for(var t:tasks) t.get(15,TimeUnit.SECONDS);
        }
        resources(f,10,50,0); assertEquals(1,gateway.cancelCalls.get());
    }

    @Test void sameMemberConcurrentCheckoutCannotSpendOrConsumeCartTwice() throws Exception {
        var f=fixture(); var member=members.findById(f.member()).orElseThrow();
        var cart=carts.findByMemberId(f.member()).orElseThrow();
        items.saveAndFlush(CartItem.builder().cart(cart).product(products.findById(f.product()).orElseThrow())
                .quantity(1).grindType(GrindType.WHOLE_BEAN).build());
        var request=new OrderDto.CreateRequest();
        Long address=addresses.findAll().stream().filter(a->a.getMember().getId().equals(member.getId())).findFirst().orElseThrow().getId();
        ReflectionTestUtils.setField(request,"addressId",address);ReflectionTestUtils.setField(request,"usedMileage",500);
        var start=new CountDownLatch(1);AtomicInteger successes=new AtomicInteger();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(()-> {start.await();try {orderService.createOrder(f.member(),request);successes.incrementAndGet();}
                catch(CustomException e) { assertEquals(ErrorCode.EMPTY_CART,e.getErrorCode()); }return null;}));
            start.countDown(); for(var task:tasks) task.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,successes.get());resources(f,8,200,0);
        List<Long> ids=orders.findAll().stream().filter(o->o.getMember().getId().equals(f.member())).map(Orders::getId).toList();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=ids.stream().map(id -> pool.submit(()->workflow.cancel(f.member(),id,false))).toList();
            for(var task:tasks) task.get(10,TimeUnit.SECONDS);
        }
        resources(f,10,1000,0);
    }

    @Test void restockAndStaleEntityStatusUpdateCannotOverwriteRefundedStock() throws Exception {
        var f=fixture();workflow.confirm(f.member(),confirm(f));
        var loaded=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var stale=pool.submit(()->tx(()-> {
                assertEquals(9,products.findById(f.product()).orElseThrow().getStockQuantity()); loaded.countDown();
                try {assertTrue(proceed.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                var r=new com.back.coffeeprod.domain.product.dto.ProductDto.StatusRequest();
                ReflectionTestUtils.setField(r,"status",ProductStatus.HIDDEN);productService.updateProductStatus(f.product(),r);
            }));
            assertTrue(loaded.await(5,TimeUnit.SECONDS));
            var refund=pool.submit(()->workflow.cancel(f.member(),f.order(),false));
            var restock=pool.submit(()-> {var r=new com.back.coffeeprod.domain.product.dto.ProductDto.StockRequest();
                ReflectionTestUtils.setField(r,"quantity",5);return productService.addStock(f.product(),r);});
            refund.get(10,TimeUnit.SECONDS);restock.get(10,TimeUnit.SECONDS);proceed.countDown();stale.get(10,TimeUnit.SECONDS);
        } finally {proceed.countDown();}
        resources(f,15,1000,0);assertEquals(ProductStatus.HIDDEN,products.findById(f.product()).orElseThrow().getStatus());
    }

    @Test void tenActualRecoveryRoundsPersistBudgetAndNewWorkerFindsDueWork() {
        var f=fixture();gateway.unknown=true;workflow.confirm(f.member(),confirm(f));
        Long id=operation(f,PaymentOperation.Type.CONFIRM).getId();
        var restarted=new PaymentWorkflow(transactions,gateway,operations);
        for(int i=0;i<10;i++) {expire(id);restarted.recoverDue();}
        var op=operations.findById(id).orElseThrow();
        assertEquals(10,op.getAttemptCount());assertEquals(PaymentOperation.Status.MANUAL_REQUIRED,op.getStatus());
        assertEquals(1,gateway.confirmCalls.get());resources(f,9,700,0);
    }

    @Test void legacyInconsistencyAndUnsupportedOrPartialPaymentNeverReplayResources() {
        var legacy=fixture(); tx(()-> {var o=orders.findLockedById(legacy.order()).orElseThrow(); o.markAsPaid(0);});
        assertEquals(ErrorCode.PAYMENT_OPERATION_CONFLICT,assertThrows(CustomException.class,()->workflow.cancel(legacy.member(),legacy.order(),true)).getErrorCode());
        assertEquals(ErrorCode.PAYMENT_OPERATION_CONFLICT,assertThrows(CustomException.class,()->workflow.reconcile(legacy.order())).getErrorCode());
        resources(legacy,9,700,0);
        var f=fixture(); workflow.confirm(f.member(),confirm(f)); gateway.unsupported=true;
        assertThrows(CustomException.class,()->workflow.cancel(f.member(),f.order(),false)); resources(f,9,797,0);
        gateway.unsupported=false;
        var partial=fixture(); workflow.confirm(partial.member(),confirm(partial)); gateway.partial=true;
        assertThrows(CustomException.class,()->workflow.cancel(partial.member(),partial.order(),false)); resources(partial,9,797,0);
        assertEquals(0,gateway.cancelCalls.get());
    }

    @Test void refundExcludesOriginalKstDateAndRebuildRequestIsRetried() {
        gateway.approvedAt=Instant.parse("2040-01-31T15:00:00Z");
        var f=fixture(); workflow.confirm(f.member(),confirm(f));
        LocalDate original=LocalDate.of(2040,2,1);
        assertTrue(rebuilds.existsById(original)); rebuildService.process(original);
        assertEquals(1,statistics.findByStatDate(original).orElseThrow().getOrderCount());
        assertFalse(rebuilds.existsById(original)); workflow.cancel(f.member(),f.order(),false);
        assertTrue(rebuilds.existsById(original)); rebuildService.recordError(original);
        assertEquals("SALES_REBUILD_FAILED",rebuilds.findById(original).orElseThrow().getLastErrorCode());
        rebuildService.process(original); assertFalse(rebuilds.existsById(original));
        var stat=statistics.findByStatDate(original).orElseThrow();
        assertEquals(0,stat.getOrderCount()); assertEquals(0,stat.getPaymentAmount());
        assertEquals(Instant.parse("2040-01-31T15:00:00Z"),payments.findByOrdersId(f.order()).orElseThrow().getPaidAt());
        assertEquals(0,sales.getSalesStatistics(original,original,com.back.coffeeprod.domain.statistics.entity.SalesStatisticsUnit.MONTHLY).getFirst().getPaymentAmount());
    }

    HttpResponse<String> http(String method,String path,String body,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(token!=null) b.header("Authorization","Bearer "+token);
        b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body==null ? "" : body));
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void rebuildRequestsRemainConsistentAcrossManualWorkerAndRefundPaths() throws Exception {
        gateway.approvedAt=Instant.parse("2042-02-01T00:00:00Z");
        var f=fixture();workflow.confirm(f.member(),confirm(f));LocalDate date=LocalDate.of(2042,2,1);
        List<Runnable> actions=List.of(()->sales.aggregateDailySales(date),()->rebuildService.process(date),
                ()->workflow.cancel(f.member(),f.order(),false),()->tx(()->rebuildService.request(gateway.approvedAt)));
        if(System.getenv("DEV_TASK4_PG_URL")!=null) {
            var start=new CountDownLatch(1);
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks=actions.stream().map(action->pool.submit(()-> {start.await();action.run();return null;})).toList();
                start.countDown();for(var task:tasks)task.get(15,TimeUnit.SECONDS);
            }
        } else actions.forEach(Runnable::run); // H2 covers function only; PG covers real advisory-lock races.
        rebuildService.process(date);
        assertFalse(rebuilds.existsById(date));assertEquals(0,statistics.findByStatDate(date).orElseThrow().getOrderCount());
        resources(f,10,1000,0);
    }
    @Test void httpAuthorizationStatusCodesAndAdminViewsRespectContract() throws Exception {
        var f=fixture(); var other=fixture();
        String owner=jwt.generateAccessToken(f.member(),Role.USER), stranger=jwt.generateAccessToken(other.member(),Role.USER);
        String path="/api/v1/orders/"+f.order()+"/cancel";
        assertEquals(401,http("POST",path,null,null).statusCode());
        assertEquals(403,http("POST",path,null,stranger).statusCode());
        assertEquals(404,http("POST","/api/v1/orders/9223372036854775807/cancel",null,owner).statusCode());
        assertEquals(403,http("GET","/api/v1/admin/orders/payment-operations",null,owner).statusCode());
        gateway.loseConfirm=true;
        String body="{\"paymentKey\":\"key-"+f.toss()+"\",\"tossOrderId\":\""+f.toss()+"\",\"amount\":"+f.amount()+"}";
        var pending=http("POST","/api/v1/payments/confirm",body,owner);
        assertEquals(202,pending.statusCode()); assertTrue(pending.body().contains("\"status\":202"));
        assertTrue(pending.body().contains("\"paymentOperationStatus\":\"UNKNOWN\""));
        var admin=Member.builder().email(UUID.randomUUID()+"@test.invalid").password("fixture").name("admin").nickname(UUID.randomUUID().toString()).role(Role.ADMIN).build();
        members.saveAndFlush(admin); String token=jwt.generateAccessToken(admin.getId(),Role.ADMIN);
        String base="/api/v1/admin/orders/"+f.order();
        assertEquals(400,http("PATCH",base+"/status","{\"status\":\"PAID\"}",token).statusCode());
        assertEquals(200,http("POST",base+"/payment-reconcile",null,token).statusCode());
        var list=http("GET","/api/v1/admin/orders/payment-operations?orderId="+f.order(),null,token);
        assertEquals(200,list.statusCode()); assertFalse(list.body().contains("paymentKey")); assertTrue(list.body().contains("operationId"));
        assertEquals(200,http("PATCH",base+"/status","{\"status\":\"CANCELED\"}",token).statusCode()); resources(f,10,1000,0);
        assertTrue(http("GET","/api/v1/orders/"+f.order(),null,owner).body().contains("\"cancelRequested\":true"));
    }

    @Test void inactiveOwnersAreDeniedButAdminCanRecover() {
        for(var state:List.of(MemberStatus.SUSPENDED,MemberStatus.WITHDRAWN)) {
            var f=fixture(); workflow.confirm(f.member(),confirm(f));
            tx(()->members.findLockedById(f.member()).orElseThrow().updateStatus(state));
            assertEquals(state==MemberStatus.SUSPENDED ? ErrorCode.SUSPENDED_MEMBER : ErrorCode.WITHDRAW_MEMBER,
                    assertThrows(CustomException.class,()->workflow.cancel(f.member(),f.order(),false)).getErrorCode());
            workflow.cancel(999L,f.order(),true); resources(f,10,1000,0);
        }
    }

    @TestConfiguration static class Config { @Bean @Primary FaultGateway faultGateway() { return new FaultGateway(); } }
    static class FaultGateway extends FakePaymentGateway {
        AtomicInteger confirmCalls=new AtomicInteger(), cancelCalls=new AtomicInteger();
        volatile boolean loseConfirm,loseCancel,rejectConfirm,rejectCancel,unknown,block,unsupported,partial;
        volatile Instant approvedAt;
        volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        @Override public GatewayPayment confirm(String key,String order,int amount,String idempotency) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); confirmCalls.incrementAndGet();
            if(block) { entered.countDown(); try { if(!release.await(8,TimeUnit.SECONDS)) throw new AssertionError("gateway timeout"); } catch(InterruptedException e){throw new RuntimeException(e);} }
            if(rejectConfirm) throw new GatewayException(true); if(unknown) throw new GatewayException(false);
            var p=super.confirm(key,order,amount,idempotency);
            if(loseConfirm) {loseConfirm=false;throw new GatewayException(false);} return adapt(p);
        }
        @Override public GatewayPayment cancelFull(String key,String reason,String idempotency) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); cancelCalls.incrementAndGet();
            if(rejectCancel) throw new GatewayException(true);
            var p=super.cancelFull(key,reason,idempotency);
            if(loseCancel) {loseCancel=false;throw new GatewayException(false);} return adapt(p);
        }
        @Override public GatewayPayment lookupByPaymentKey(String key) { return adapt(super.lookupByPaymentKey(key)); }
        @Override public GatewayPayment lookupByOrderId(String id) { return adapt(super.lookupByOrderId(id)); }
        GatewayPayment adapt(GatewayPayment p) {
            if(p==null)return null;
            return new GatewayPayment(p.paymentKey(),p.orderId(),partial ? "PARTIAL_CANCELED" : p.status(),p.totalAmount(),p.balanceAmount(),
                    p.method(),approvedAt==null ? p.approvedAt() : approvedAt,!unsupported,p.cancels());
        }
        void reset() { clearState();confirmCalls.set(0);cancelCalls.set(0);loseConfirm=false;loseCancel=false;rejectConfirm=false;rejectCancel=false;unknown=false;block=false;unsupported=false;partial=false;approvedAt=null;entered=new CountDownLatch(1);release=new CountDownLatch(1); }
    }
}

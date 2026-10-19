package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.entity.*;
import com.back.coffeeprod.domain.payment.entity.*;
import com.back.coffeeprod.domain.product.dto.ProductDto;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.statistics.entity.SalesStatisticsUnit;
import com.back.coffeeprod.domain.statistics.scheduler.SalesRebuildScheduler;
import com.back.coffeeprod.global.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class QaPaymentAcceptanceTest extends QaPaymentSupport {
    @ParameterizedTest @ValueSource(booleans={false,true})
    @DisplayName("AT1-A01 사용자와 관리자의 미결제 취소는 PG 호출 없이 한 번만 복구한다")
    void pendingCancellation(boolean isAdmin) throws Exception {
        var f=fixture();String auth=isAdmin?admin():token(f.member());
        String path=isAdmin?"/api/v1/admin/orders/"+f.order()+"/status":cancelPath(f);
        for(int i=0;i<2;i++) {
            var view=data(http(isAdmin?"PATCH":"POST",path,isAdmin?"{\"status\":\"CANCELED\"}":null,auth),200);
            assertEquals("CANCELED",view.path("status").asText());assertTrue(view.path("cancelRequested").asBoolean());
            resources(f,10,1000,0);
        }
        assertTrue(PG.calls.isEmpty());assertTrue(payments.findByOrdersId(f.order()).isEmpty());
    }

    @ParameterizedTest @CsvSource({"카드,false","카드,true","간편결제,false","간편결제,true"})
    @DisplayName("AT1-A02 카드와 카드 간편결제의 전체 취소는 배송비 포함 전액을 한 번만 환불한다")
    void paidCancellation(String method,boolean isAdmin) throws Exception {
        PG.method=method;var f=fixture();attempt(f);
        String auth=isAdmin?admin():token(f.member());String path=isAdmin?"/api/v1/admin/orders/"+f.order()+"/status":cancelPath(f);
        for(int i=0;i<2;i++) {
            var view=data(http(isAdmin?"PATCH":"POST",path,isAdmin?"{\"status\":\"CANCELED\"}":null,auth),200);
            assertEquals("CANCELED",view.path("status").asText());assertEquals("SUCCEEDED",view.path("paymentOperationStatus").asText());resources(f,10,1000,0);
        }
        var p=payments.findByOrdersId(f.order()).orElseThrow();assertEquals(PaymentStatus.REFUNDED,p.getStatus());
        assertEquals(PG.approvedAt,p.getPaidAt());assertEquals(PG.canceledAt,p.getRefundedAt());
        assertEquals(12700,f.amount());assertEquals(1,PG.posts("/cancel"));assertEquals(1,PG.posts("/confirm"));
        var op=operation(f,PaymentOperation.Type.CANCEL_FULL);assertEquals(f.amount(),op.getAmount());
        var post=PG.calls.stream().filter(c->c.path().endsWith("/cancel")).findFirst().orElseThrow();
        assertEquals(op.getIdempotencyKey(),post.key());assertEquals(isAdmin?"관리자 주문 전체 취소":"사용자 주문 전체 취소",JSON.readTree(post.body()).path("cancelReason").asText());
        assertFalse(JSON.readTree(post.body()).has("cancelAmount"));assertNotNull(op.getPgTransactionKey());
    }

    @Test @DisplayName("AT1-A03 HTTP 권한과 소유권 거부는 결제와 자원을 변경하지 않는다")
    void authorization() throws Exception {
        var f=fixture();var other=fixture();String owner=token(f.member()),stranger=token(other.member());
        assertEquals(401,http("POST",cancelPath(f),null,null).statusCode());
        assertEquals(403,http("POST",cancelPath(f),null,stranger).statusCode());
        assertEquals(404,http("POST","/api/v1/orders/9223372036854775807/cancel",null,owner).statusCode());
        for(String path:List.of("/api/v1/admin/orders/payment-operations","/api/v1/admin/orders/"+f.order()+"/payment-reconcile")) {
            String verb=path.endsWith("reconcile")?"POST":"GET";
            assertEquals(401,http(verb,path,null,null).statusCode());assertEquals(403,http(verb,path,null,owner).statusCode());
        }
        resources(f,9,700,0);assertTrue(PG.calls.isEmpty());assertEquals(OrderStatus.PENDING,orders.findById(f.order()).orElseThrow().getStatus());
    }

    @ParameterizedTest @EnumSource(value=MemberStatus.class,names={"SUSPENDED","WITHDRAWN"})
    @DisplayName("AT1-A03 정지와 탈퇴 회원의 HTTP 취소는 거부하고 관리자의 복구는 허용한다")
    void inactiveOwner(MemberStatus status) throws Exception {
        var f=fixture();attempt(f);String owner=token(f.member()),admin=admin();
        tx(()->members.findLockedById(f.member()).orElseThrow().updateStatus(status));
        int response=http("POST",cancelPath(f),null,owner).statusCode();assertEquals(status==MemberStatus.SUSPENDED?403:401,response);
        assertEquals(0,PG.posts("/cancel"));resources(f,9,797,0);
        data(http("PATCH","/api/v1/admin/orders/"+f.order()+"/status","{\"status\":\"CANCELED\"}",admin),200);resources(f,10,1000,0);
    }

    @Test @DisplayName("AT1-A04 직접 결제완료와 운송장 누락 및 배송 이후 취소를 HTTP에서 거부한다")
    void shippingGuards() throws Exception {
        var f=fixture();String auth=admin(),path="/api/v1/admin/orders/"+f.order()+"/status";
        assertEquals(400,http("PATCH",path,"{\"status\":\"PAID\"}",auth).statusCode());assertTrue(PG.calls.isEmpty());
        attempt(f);assertEquals(400,http("PATCH",path,"{\"status\":\"SHIPPED\"}",auth).statusCode());
        data(http("PATCH",path,"{\"status\":\"SHIPPED\",\"trackingNo\":\"QA-TRACK\"}",auth),200);
        for(String status:List.of("SHIPPED","DELIVERED")) {
            assertEquals(400,http("POST",cancelPath(f),null,token(f.member())).statusCode());
            assertEquals(400,http("PATCH",path,"{\"status\":\"CANCELED\"}",auth).statusCode());
            if(status.equals("SHIPPED"))data(http("PATCH",path,"{\"status\":\"DELIVERED\"}",auth),200);
        }
        assertEquals(0,PG.posts("/cancel"));resources(f,9,797,0);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    @DisplayName("AT1-A05 승인 거부와 환불 거부는 서로 다른 보상 계약을 유지한다")
    void rejected(boolean refund) throws Exception {
        var f=fixture();if(refund) {attempt(f);PG.cancelFault=QaPgServer.Fault.REJECT;}
        else {PG.confirmFault=QaPgServer.Fault.REJECT;ready(f);}
        String owner=token(f.member());String body=JSON.writeValueAsString(Map.of("paymentKey",request(f).getPaymentKey(),"tossOrderId",f.toss(),"amount",f.amount()));
        assertEquals(400,http("POST",refund?cancelPath(f):"/api/v1/payments/confirm",refund?null:body,owner).statusCode());
        var op=operation(f,refund?PaymentOperation.Type.CANCEL_FULL:PaymentOperation.Type.CONFIRM);assertEquals(PaymentOperation.Status.REJECTED,op.getStatus());
        assertEquals(refund?OrderStatus.PAID:OrderStatus.CANCELED,orders.findById(f.order()).orElseThrow().getStatus());
        resources(f,refund?9:10,refund?797:1000,0);
        if(refund){assertEquals(PaymentStatus.SUCCESS,payments.findByOrdersId(f.order()).orElseThrow().getStatus());assertFalse(orders.findById(f.order()).orElseThrow().isCancelRequested());}
        else assertTrue(payments.findByOrdersId(f.order()).isEmpty());
    }

    @ParameterizedTest @EnumSource(value=QaPgServer.Fault.class,names={"FIVE_HUNDRED","CLOSE","EMPTY","MALFORMED","MISMATCH","TIMEOUT","LOST_SUCCESS"})
    @DisplayName("AT1-A06 승인 전송과 응답 장애는 UNKNOWN 동안 선점 자원을 보존한다")
    void approvalFault(QaPgServer.Fault fault) {
        var f=fixture();PG.confirmFault=fault;assertEquals(202,attempt(f).status());
        assertEquals(PaymentOperation.Status.UNKNOWN,operation(f,PaymentOperation.Type.CONFIRM).getStatus());resources(f,9,700,0);
        assertTrue(payments.findByOrdersId(f.order()).isEmpty());PG.confirmFault=QaPgServer.Fault.NONE;
        if(PG.state.isEmpty()) {PG.lookupFault=QaPgServer.Fault.NOT_FOUND;assertEquals(202,workflow.reconcile(f.order()).status());resources(f,9,700,0);}
        else {expire(operation(f,PaymentOperation.Type.CONFIRM).getId());workflow.recoverDue();assertEquals(200,workflow.reconcile(f.order()).status());resources(f,9,797,0);assertEquals(1,PG.posts("/confirm"));}
    }

    @ParameterizedTest @EnumSource(value=QaPgServer.Fault.class,names={"FIVE_HUNDRED","CLOSE","EMPTY","MALFORMED","MISMATCH","TIMEOUT","LOST_SUCCESS"})
    @DisplayName("AT1-A06 환불 응답 장애는 202로 유지하고 조회 확정 후에만 자원을 복구한다")
    void refundFault(QaPgServer.Fault fault) throws Exception {
        var f=fixture();attempt(f);PG.cancelFault=fault;
        var view=data(http("POST",cancelPath(f),null,token(f.member())),202);
        assertEquals("PAID",view.path("status").asText());assertEquals("UNKNOWN",view.path("paymentOperationStatus").asText());assertTrue(view.path("cancelRequested").asBoolean());
        resources(f,9,797,0);assertEquals(PaymentStatus.SUCCESS,payments.findByOrdersId(f.order()).orElseThrow().getStatus());
        String auth=admin();assertEquals(409,http("PATCH","/api/v1/admin/orders/"+f.order()+"/status","{\"status\":\"SHIPPED\",\"trackingNo\":\"QA\"}",auth).statusCode());
        PG.cancelFault=QaPgServer.Fault.NONE;data(http("POST","/api/v1/admin/orders/"+f.order()+"/payment-reconcile",null,auth),200);resources(f,10,1000,0);
        var posts=PG.calls.stream().filter(c->c.path().endsWith("/cancel")).toList();assertEquals(1,posts.stream().map(QaPgServer.Call::key).distinct().count());
        assertEquals(1,posts.stream().map(QaPgServer.Call::body).distinct().count());
        if(fault==QaPgServer.Fault.LOST_SUCCESS||fault==QaPgServer.Fault.TIMEOUT)assertEquals(1,posts.size());
        var listing=data(http("GET","/api/v1/admin/orders/payment-operations?orderId="+f.order()+"&status=SUCCEEDED",null,auth),200);
        assertTrue(listing.path("content").isArray());assertEquals(2,listing.path("content").size());
        for(var item:listing.path("content")){assertFalse(item.has("paymentKey"));assertTrue(item.has("operationId"));assertEquals("SUCCEEDED",item.path("status").asText());}
    }

    @Test @DisplayName("AT1-A07 환불 성공 후 DB 실패는 전체 롤백하고 조회 복구를 한 번 반영한다")
    void refundCommitFailure() {
        var f=fixture();attempt(f);
        jdbc.update("update product set stock_quantity=? where product_id=?",Integer.MAX_VALUE,f.product());
        assertEquals(202,workflow.cancel(f.member(),f.order(),false).status());
        resources(f,Integer.MAX_VALUE,797,0);assertEquals(PaymentStatus.SUCCESS,payments.findByOrdersId(f.order()).orElseThrow().getStatus());
        assertEquals(PaymentOperation.Status.IN_FLIGHT,operation(f,PaymentOperation.Type.CANCEL_FULL).getStatus());
        jdbc.update("update product set stock_quantity=9 where product_id=?",f.product());expire(operation(f,PaymentOperation.Type.CANCEL_FULL).getId());
        workflow.recoverDue();assertEquals(200,workflow.reconcile(f.order()).status());assertEquals(200,workflow.reconcile(f.order()).status());
        resources(f,10,1000,0);assertEquals(1,PG.posts("/cancel"));
    }

    @Test @DisplayName("AT1-A08 중복 승인과 환불 및 동일 회원 여러 주문의 동시 처리는 한 번만 반영한다")
    void concurrentPaymentAndMileage() throws Exception {
        var f=fixture();together(Collections.nCopies(4,()->attempt(f)));
        var second=fixture(f.member(),300);attempt(second);
        List<Runnable> actions=new ArrayList<>();for(int i=0;i<4;i++){actions.add(()->workflow.cancel(f.member(),f.order(),false));actions.add(()->workflow.cancel(f.member(),second.order(),true));}
        actions.add(()->tx(()->members.findLockedById(f.member()).orElseThrow().addMileage(150)));together(actions);
        resources(f,10,1150,0);resources(second,10,1150,0);assertEquals(2,PG.posts("/confirm"));assertEquals(2,PG.posts("/cancel"));
        assertEquals(2,payments.findAll().stream().filter(p->p.getOrders().getId().equals(f.order())||p.getOrders().getId().equals(second.order())).count());
    }

    @Test @DisplayName("AT1-A08 배송과 환불이 경합해도 동시에 성공하지 않는다")
    void shippingRefundRace() throws Exception {
        var f=fixture();attempt(f);var ship=new OrderDto.StatusUpdateRequest();
        ReflectionTestUtils.setField(ship,"status",OrderStatus.SHIPPED);ReflectionTestUtils.setField(ship,"trackingNo","QA");
        var shipSuccess=new java.util.concurrent.atomic.AtomicBoolean();var cancelSuccess=new java.util.concurrent.atomic.AtomicBoolean();
        together(List.of(()->{try{transactions.ship(f.order(),ship);shipSuccess.set(true);}catch(CustomException e){assertTrue(Set.of(ErrorCode.INVALID_ORDER_STATUS,ErrorCode.PAYMENT_OPERATION_CONFLICT).contains(e.getErrorCode()));}},
                ()->{try{workflow.cancel(f.member(),f.order(),false);cancelSuccess.set(true);}catch(CustomException e){assertEquals(ErrorCode.INVALID_ORDER_STATUS,e.getErrorCode());}}));
        assertNotEquals(shipSuccess.get(),cancelSuccess.get());
        var order=orders.findById(f.order()).orElseThrow();assertEquals(shipSuccess.get()?OrderStatus.SHIPPED:OrderStatus.CANCELED,order.getStatus());
        resources(f,shipSuccess.get()?9:10,shipSuccess.get()?797:1000,0);
    }

    @Test @DisplayName("AT1-A10 오래된 상품 전체 수정과 입고 및 환불 경합은 재고 증감을 보존한다")
    void stockRace() throws Exception {
        var f=fixture();attempt(f);var loaded=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var stale=pool.submit(()->tx(()->{products.findById(f.product()).orElseThrow();loaded.countDown();
                try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                var r=new ProductDto.Request();ReflectionTestUtils.setField(r,"categoryId",products.findById(f.product()).orElseThrow().getCategory().getId());
                ReflectionTestUtils.setField(r,"sku",products.findById(f.product()).orElseThrow().getSku());ReflectionTestUtils.setField(r,"name","QA 수정");
                ReflectionTestUtils.setField(r,"weightGrams",200);ReflectionTestUtils.setField(r,"price",10000);ReflectionTestUtils.setField(r,"stockQuantity",9);
                ReflectionTestUtils.setField(r,"roastLevel",com.back.coffeeprod.domain.product.entity.RoastLevel.MEDIUM);productService.updateProduct(f.product(),r);
            }));assertTrue(loaded.await(5,TimeUnit.SECONDS));
            together(List.of(()->workflow.cancel(f.member(),f.order(),false),()->{var r=new ProductDto.StockRequest();ReflectionTestUtils.setField(r,"quantity",5);productService.addStock(f.product(),r);}));
            release.countDown();stale.get(15,TimeUnit.SECONDS);
        }finally{release.countDown();}
        resources(f,15,1000,0);assertEquals("QA 수정",products.findById(f.product()).orElseThrow().getName());
    }

    @ParameterizedTest @ValueSource(ints={0,200})
    @DisplayName("AT1-A12 사용한 적립금의 환불과 이후 유입은 회수 대기액부터 상계한다")
    void recoveryDue(int remaining) {
        var f=fixture(null,0);attempt(f);
        tx(()->{var m=members.findLockedById(f.member()).orElseThrow();m.useMileage(1100-remaining);m.settleMileage(0,1000);});
        workflow.cancel(f.member(),f.order(),false);resources(f,10,0,1100-remaining);
        tx(()->members.findLockedById(f.member()).orElseThrow().addMileage(300));resources(f,10,0,800-remaining);
        tx(()->members.findLockedById(f.member()).orElseThrow().addMileage(1000));resources(f,10,200+remaining,0);
    }

    @Test @DisplayName("AT1-A13 KST 월 경계의 환불은 원결제일 매출을 제외하고 실패한 집계 요청을 보존한다")
    void statisticsBoundaryAndRetry() throws Exception {
        PG.approvedAt=Instant.parse("2049-01-31T14:59:59Z");var before=fixture();attempt(before);
        PG.approvedAt=Instant.parse("2049-01-31T15:00:00Z");var after=fixture();attempt(after);
        LocalDate jan=LocalDate.of(2049,1,31),feb=jan.plusDays(1);rebuildService.process(jan);rebuildService.process(feb);
        assertEquals(1,statistics.findByStatDate(jan).orElseThrow().getOrderCount());assertEquals(1,statistics.findByStatDate(feb).orElseThrow().getOrderCount());
        workflow.cancel(before.member(),before.order(),false);
        jdbc.execute("alter table sales_statistics add constraint qa6_fail_rebuild check (stat_date <> DATE '2049-01-31' or order_count > 0)");
        try {new SalesRebuildScheduler(rebuilds,rebuildService).rebuild();assertTrue(rebuilds.existsById(jan));assertEquals("SALES_REBUILD_FAILED",rebuilds.findById(jan).orElseThrow().getLastErrorCode());}
        finally{jdbc.execute("alter table sales_statistics drop constraint qa6_fail_rebuild");}
        List<Runnable> actions=List.of(()->rebuildService.process(jan),()->sales.aggregateDailySales(jan),()->tx(()->rebuildService.request(Instant.parse("2049-01-31T14:59:59Z"))));
        if(postgres())together(actions);else actions.forEach(Runnable::run);
        rebuildService.process(jan);assertFalse(rebuilds.existsById(jan));
        var excluded=statistics.findByStatDate(jan).orElseThrow();assertEquals(0,excluded.getOrderCount());assertEquals(0,excluded.getPaymentAmount());
        assertEquals(0,excluded.getProductSalesAmount());assertEquals(0,excluded.getDeliveryFeeAmount());assertEquals(0,excluded.getUsedMileageAmount());
        assertEquals(12700,sales.getSalesStatistics(jan,feb,SalesStatisticsUnit.YEARLY).getFirst().getPaymentAmount());
        var monthly=sales.getSalesStatistics(jan,feb,SalesStatisticsUnit.MONTHLY);assertEquals(0,monthly.getFirst().getPaymentAmount());assertEquals(12700,monthly.getLast().getPaymentAmount());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    @DisplayName("AT1-A11 복구 10회와 15일 경과 후에는 자동 POST를 중단하고 수동 확인으로 남긴다")
    void recoveryBudget(boolean aged) {
        var f=fixture();PG.confirmFault=QaPgServer.Fault.CLOSE;assertEquals(202,attempt(f).status());
        Long id=operation(f,PaymentOperation.Type.CONFIRM).getId();String key=operation(f,PaymentOperation.Type.CONFIRM).getIdempotencyKey();
        if(aged) jdbc.update("update payment_operation set first_sent_at=? where id=?",java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(16))),id);
        else for(int i=0;i<10;i++){expire(id);workflow.recoverDue();}
        if(aged){expire(id);workflow.recoverDue();assertEquals(ErrorCode.PAYMENT_OPERATION_CONFLICT,assertThrows(CustomException.class,()->workflow.reconcile(f.order())).getErrorCode());}
        assertEquals(PaymentOperation.Status.MANUAL_REQUIRED,operations.findById(id).orElseThrow().getStatus());
        if(!aged)assertEquals(10,operations.findById(id).orElseThrow().getAttemptCount());
        assertEquals(1,PG.posts("/confirm"));resources(f,9,700,0);assertEquals(key,operations.findById(id).orElseThrow().getIdempotencyKey());
        assertThrows(CustomException.class,()->workflow.reconcile(f.order()));assertEquals(1,PG.posts("/confirm"));
    }

    @Test @DisplayName("AT1-A11 worker 경쟁과 만료 임대의 늦은 응답은 완료 결과를 덮어쓰지 않는다")
    void leaseAndWorkerRace() throws Exception {
        var f=fixture();var prep=transactions.prepareConfirm(f.member(),request(f));var old=transactions.claim(prep.operationId());
        assertNotNull(old);expire(prep.operationId());
        together(List.of(workflow::recoverDue,workflow::recoverDue));
        // A temporary missing lookup must not POST or restore the reserved resources.
        assertEquals(PaymentOperation.Status.UNKNOWN,operations.findById(prep.operationId()).orElseThrow().getStatus());resources(f,9,700,0);
        var snapshot=new com.back.coffeeprod.domain.payment.gateway.TossPaymentGateway("qa",PG.url(),new com.back.coffeeprod.domain.payment.gateway.PaymentRecoveryProperties(Duration.ofMillis(150),Duration.ofMillis(650),Duration.ofSeconds(2)))
                .confirm(old.paymentKey(),old.tossOrderId(),old.amount(),old.idempotencyKey());
        transactions.apply(old,snapshot,false,false,null);assertTrue(payments.findByOrdersId(f.order()).isEmpty());
        expire(prep.operationId());workflow.recoverDue();assertEquals(200,workflow.reconcile(f.order()).status());
        transactions.apply(old,null,true,false,null);resources(f,9,797,0);assertEquals(PaymentOperation.Status.SUCCEEDED,operations.findById(prep.operationId()).orElseThrow().getStatus());
        assertEquals(1,PG.posts("/confirm"));
    }

    @ParameterizedTest @ValueSource(strings={"missing-payment","canceled-success","unsupported","partial"})
    @DisplayName("AT1-A14 기존 불일치와 미지원 수단 및 부분 취소는 자원을 재지급하지 않는다")
    void manualCases(String mode) throws Exception {
        var f=fixture();
        if(mode.equals("missing-payment"))tx(()->orders.findLockedById(f.order()).orElseThrow().markAsPaid(0));
        else {
            if(mode.equals("unsupported"))PG.card=false;
            attempt(f);
            if(mode.equals("canceled-success"))tx(()->orders.findLockedById(f.order()).orElseThrow().cancel());
            if(mode.equals("partial")) {var p=PG.state.get(request(f).getPaymentKey());p.put("status","PARTIAL_CANCELED");p.put("balanceAmount",1);}
        }
        String auth=admin();assertEquals(409,http("PATCH","/api/v1/admin/orders/"+f.order()+"/status","{\"status\":\"CANCELED\"}",auth).statusCode());
        assertEquals(409,http("POST","/api/v1/admin/orders/"+f.order()+"/payment-reconcile",null,auth).statusCode());
        assertEquals(PaymentOperation.Status.MANUAL_REQUIRED,operation(f,PaymentOperation.Type.CANCEL_FULL).getStatus());
        resources(f,9,mode.equals("missing-payment")?700:797,0);assertEquals(0,PG.posts("/cancel"));
    }

    @Test @DisplayName("AT1-A07 승인 성공 후 적립 저장 실패는 롤백하고 PG 조회로 복구한다")
    void approvalCommitFailure() {
        var f=fixture();tx(()->members.findLockedById(f.member()).orElseThrow().addMileage(Integer.MAX_VALUE-700));
        assertEquals(202,attempt(f).status());
        assertEquals(OrderStatus.PENDING,orders.findById(f.order()).orElseThrow().getStatus());assertTrue(payments.findByOrdersId(f.order()).isEmpty());resources(f,9,Integer.MAX_VALUE,0);
        tx(()->members.findLockedById(f.member()).orElseThrow().useMileage(Integer.MAX_VALUE-700));expire(operation(f,PaymentOperation.Type.CONFIRM).getId());
        workflow.recoverDue();assertEquals(200,workflow.reconcile(f.order()).status());resources(f,9,797,0);assertEquals(1,PG.posts("/confirm"));
    }

    @Test @DisplayName("AT1-A09 동일 회원의 동시 주문은 장바구니와 사용 마일리지를 중복 소비하지 않는다")
    void concurrentCheckout() throws Exception {
        var f=fixture();var member=members.findById(f.member()).orElseThrow();var cart=carts.findByMemberId(f.member()).orElseThrow();
        items.saveAndFlush(com.back.coffeeprod.domain.cart.entity.CartItem.builder().cart(cart).product(products.findById(f.product()).orElseThrow()).quantity(1)
                .grindType(com.back.coffeeprod.domain.cart.entity.GrindType.WHOLE_BEAN).build());
        var r=new OrderDto.CreateRequest();ReflectionTestUtils.setField(r,"addressId",addresses.findAll().stream().filter(a->a.getMember().getId().equals(member.getId())).findFirst().orElseThrow().getId());
        ReflectionTestUtils.setField(r,"usedMileage",500);var success=new java.util.concurrent.atomic.AtomicInteger();
        Runnable checkout=()->{try{orderService.createOrder(f.member(),r);success.incrementAndGet();}catch(CustomException e){assertEquals(ErrorCode.EMPTY_CART,e.getErrorCode());}};
        together(List.of(checkout,checkout));assertEquals(1,success.get());resources(f,8,200,0);
        assertEquals(2,orders.findAll().stream().filter(o->o.getMember().getId().equals(f.member())).count());
    }

    @Test @DisplayName("AT1-A10 서로 반대 상품 순서의 동시 주문과 취소는 교착 없이 재고를 보존한다")
    void orderedProductLocks() throws Exception {
        var a=fixture();var b=fixture();workflow.cancel(a.member(),a.order(),false);workflow.cancel(b.member(),b.order(),false);
        List<OrderDto.CreateRequest> requests=new ArrayList<>();List<Long> created=new CopyOnWriteArrayList<>();
        for(var f:List.of(a,b)) {
            var cart=carts.findByMemberId(f.member()).orElseThrow();
            for(Long id:f==a?List.of(a.product(),b.product()):List.of(b.product(),a.product()))items.saveAndFlush(com.back.coffeeprod.domain.cart.entity.CartItem.builder()
                    .cart(cart).product(products.findById(id).orElseThrow()).quantity(1).grindType(com.back.coffeeprod.domain.cart.entity.GrindType.WHOLE_BEAN).build());
            var r=new OrderDto.CreateRequest();ReflectionTestUtils.setField(r,"addressId",addresses.findAll().stream().filter(address->address.getMember().getId().equals(f.member())).findFirst().orElseThrow().getId());requests.add(r);
        }
        together(List.of(()->created.add(orderService.createOrder(a.member(),requests.getFirst()).getOrderId()),()->created.add(orderService.createOrder(b.member(),requests.getLast()).getOrderId())));
        assertEquals(2,created.size());resources(a,8,1000,0);resources(b,8,1000,0);
        together(created.stream().map(id->(Runnable)()->workflow.cancel(999L,id,true)).toList());resources(a,10,1000,0);resources(b,10,1000,0);assertTrue(PG.calls.isEmpty());
    }

    @Test @DisplayName("AT1-A16 진행 중 다른 키와 금액은 409이고 목록과 회원 응답은 추가 필드를 보존한다")
    void schemaAndConflict() throws Exception {
        var f=fixture();PG.confirmFault=QaPgServer.Fault.CLOSE;assertEquals(202,attempt(f).status());
        for(boolean changeKey:List.of(false,true)) {
            String body=JSON.writeValueAsString(Map.of("paymentKey",changeKey?"qa-other-key":request(f).getPaymentKey(),"tossOrderId",f.toss(),"amount",changeKey?f.amount():f.amount()+1));
            assertEquals(409,http("POST","/api/v1/payments/confirm",body,token(f.member())).statusCode());resources(f,9,700,0);
        }
        var list=data(http("GET","/api/v1/orders",null,token(f.member())),200);var row=list.path("content").get(0);
        assertEquals("UNKNOWN",row.path("paymentOperationStatus").asText());assertFalse(row.path("cancelRequested").asBoolean());
        var adminList=data(http("GET","/api/v1/admin/orders",null,admin()),200);
        assertTrue(adminList.path("content").get(0).has("paymentOperationStatus"));assertTrue(adminList.path("content").get(0).has("cancelRequested"));
        var member=data(http("GET","/api/v1/members/me",null,token(f.member())),200);assertEquals(0,member.path("mileageRecoveryDue").asInt());assertTrue(member.has("mileageRecoveryDue"));
        assertEquals(1,PG.posts("/confirm"));assertEquals(1,operations.findAll().stream().filter(o->o.getOrders().getId().equals(f.order())).count());
    }
}

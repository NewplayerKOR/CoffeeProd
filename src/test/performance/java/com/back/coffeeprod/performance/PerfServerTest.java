package com.back.coffeeprod.performance;

import com.back.coffeeprod.CoffeeprodApplication;
import com.back.coffeeprod.domain.cart.dto.CartDto;
import com.back.coffeeprod.domain.cart.service.CartService;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.back.coffeeprod.domain.payment.entity.PaymentOperation;
import com.back.coffeeprod.domain.payment.repository.PaymentOperationRepository;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.payment.service.PaymentWorkflow;
import com.back.coffeeprod.domain.product.service.ProductService;
import com.back.coffeeprod.domain.qna.service.QnaService;
import com.back.coffeeprod.domain.review.service.ReviewService;
import com.back.coffeeprod.domain.recommendation.dto.CoffeeRecommendationDto;
import com.back.coffeeprod.domain.recommendation.service.CoffeeRecommendationService;
import com.back.coffeeprod.domain.statistics.entity.SalesStatisticsUnit;
import com.back.coffeeprod.domain.statistics.service.*;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.global.security.jwt.*;
import com.back.coffeeprod.global.security.auth.CustomUserDetailsService;
import com.back.coffeeprod.global.security.handler.RestAuthenticationEntryPoint;
import io.jsonwebtoken.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs only with -PperfServer, against a restored, owned baseline. No production optimizations. */
@Tag("server")
@SpringBootTest(classes=CoffeeprodApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"prod","perf"})
class PerfServerTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final LocalDate END=LocalDate.of(2026,6,30);
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource static void settings(DynamicPropertyRegistry p) {
        PerfGuard.validate(System.getenv());
        p.add("spring.datasource.url",()->System.getenv("DB_URL"));
        p.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        p.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        p.add("pg.toss.base-url",()->System.getenv("PERF_PG_URL"));
        p.add("pg.toss.secret-key",()->"perf-synthetic");
        p.add("spring.jpa.properties.hibernate.generate_statistics",()->true);
        p.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());
    }
    @Autowired JdbcTemplate db;
    @Autowired EntityManagerFactory emf;
    @Autowired CartService carts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentOperationRepository operations;
    @Autowired PaymentWorkflow payments;
    @Autowired CoffeeRecommendationService recommendations;
    @Autowired ProductService products;
    @Autowired SalesStatisticsService sales;
    @Autowired SalesRebuildService rebuild;
    @Autowired StatisticsDateLock dateLock;
    @Autowired ReviewService reviews;
    @Autowired QnaService qnas;
    @Autowired JwtUtil jwt;
    @Autowired com.back.coffeeprod.global.security.service.RefreshTokenService refreshTokens;
    @Autowired CustomUserDetailsService userDetails;
    @Autowired RestAuthenticationEntryPoint entry;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    long member,other,product,address;
    final Map<String,Object> report=new LinkedHashMap<>();
    final List<Map<String,Object>> samples=new ArrayList<>();
    @BeforeEach void ownedBaseline() {
        assertEquals("coffeeprod_perf_phase2",db.queryForObject("select current_database()",String.class));
        assertEquals(18,db.queryForObject("select count(*) from flyway_schema_history where success and installed_rank<=18",Integer.class));
        member=id("select member_id from member where email='loadtest-user-000001@coffeeprod.local'");
        other=id("select member_id from member where email='loadtest-user-000002@coffeeprod.local'");
        address=id("select address_id from address where member_id="+member+" and is_default");
        product=id("select min(product_id) from product where status='ON_SALE' and stock_quantity>0");
        report.put("sourceHash",System.getenv("PERF_SOURCE_HASH"));report.put("measurementHash",System.getenv("PERF_MEASUREMENT_HASH"));
        report.put("variant",System.getenv().getOrDefault("PERF_VARIANT","catalog"));report.put("samples",samples);
        report.put("scope","server JVM diagnostics; not HTTP load timing");
    }
    @AfterEach void preserve() throws Exception {
        String run=System.getenv("PERF_RUN_ID"),phase=System.getenv("PERF_PHASE");PerfGuard.validateRunId(run);
        if(!List.of("before","after").contains(phase))throw new IllegalArgumentException("Invalid phase");
        String candidate=System.getenv("PERF_CANDIDATE"), variant=System.getenv().getOrDefault("PERF_VARIANT","catalog");
        PerfGuard.validateRunId(variant);
        String sample=System.getenv().getOrDefault("PERF_SAMPLE","manual");PerfGuard.validateRunId(sample);
        Path path=Path.of(System.getenv().getOrDefault("PERF_RESULTS","build/perf-phase2"),run,phase,sample,"diagnostics","case"+candidate+"-"+variant);
        Files.createDirectories(path);Files.writeString(path.resolve("measurements.json"),JSON.writeValueAsString(report));
    }
    long id(String sql){return Objects.requireNonNull(db.queryForObject(sql,Long.class));}
    <T>T dto(Object object,Class<T> type){return JSON.readValue(JSON.writeValueAsString(object),type);}
    <T>T tx(Supplier<T> work){return new TransactionTemplate(transactions).execute(s->work.get());}
    <T>T measure(String name,Supplier<T> action) {
        var stats=emf.unwrap(SessionFactory.class).getStatistics();stats.clear();SqlCapture.reset();
        var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        long cpu=os.getProcessCpuTime(),start=System.nanoTime();T value=action.get();
        Map<String,Object> result=new LinkedHashMap<>();result.put("name",name);result.put("elapsedNanos",System.nanoTime()-start);result.put("cpuNanos",os.getProcessCpuTime()-cpu);
        result.put("hibernateStatements",stats.getPrepareStatementCount());result.put("entities",stats.getEntityLoadCount());result.put("collections",stats.getCollectionLoadCount());result.put("sql",SqlCapture.templates());samples.add(result);return value;
    }
    JsonNode http(String method,String path,Object body,String token,int status) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofMinutes(7)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        var response=HTTP.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(status,response.statusCode(),"HTTP status (credentials omitted)");return JSON.readTree(response.body()).path("data");
    }
    JsonNode pg(String method,Object body) throws Exception {
        var r=HTTP.send(HttpRequest.newBuilder(URI.create(System.getenv("PERF_PG_URL")+"/__control")).header("Content-Type","application/json")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,r.statusCode());return JSON.readTree(r.body());
    }
    List<Long> saleProducts(int n){return db.queryForList("select product_id from product where status='ON_SALE' order by product_id limit ?",Long.class,n);}
    void add(long who,long p,int qty,String grind){carts.addItem(who,dto(Map.of("productId",p,"quantity",qty,"grindType",grind),CartDto.AddRequest.class));}
    OrderDto.DetailResponse create(long who,long addressId){return orders.createOrder(who,dto(Map.of("addressId",addressId,"usedMileage",0),OrderDto.CreateRequest.class));}
    Map<String,Object> balances(){return db.queryForMap("select (select sum(stock_quantity) from product) stock,(select sum(mileage) from member) mileage,(select sum(mileage_recovery_due) from member) due,(select count(*) from orders) orders,(select count(*) from order_item) items");}
    List<Object> statsSnapshot(){return new ArrayList<>(db.queryForList("select * from sales_statistics order by stat_date"));}
    void clearCart(long who){db.update("delete from cart_item where cart_id in(select cart_id from cart where member_id=?)",who);}
    <T> List<T> parallel(int n,Callable<T> work) throws Exception {
        try(var executor=Executors.newFixedThreadPool(n)) {
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();
            for(int i=0;i<n;i++)futures.add(executor.submit(()->{gate.await();return work.call();}));gate.countDown();
            var results=new ArrayList<T>();for(var f:futures)results.add(f.get(90,TimeUnit.SECONDS));return results;
        }
    }
    @Test void case1Recommendation() {
        var before=balances();var output=new ArrayList<Object>();
        for(Boolean decaf:Arrays.asList(null,true,false))for(int limit:List.of(5,10)) {
            Map<String,Object> input=new LinkedHashMap<>();input.put("decaf",decaf);input.put("limit",limit);input.put("preferredAcidity",3);input.put("preferredBody",4);
            var request=dto(input,CoffeeRecommendationDto.Request.class);
            var rows=measure("recommend-decaf-"+decaf+"-"+limit,()->recommendations.recommend(request));
            assertEquals(JSON.valueToTree(rows),JSON.valueToTree(recommendations.recommend(request)));
            assertTrue(rows.size()<=limit);Set<Long> ids=new HashSet<>();
            for(int i=0;i<rows.size();i++) {
                var row=rows.get(i);assertTrue(ids.add(row.getProductId()));assertTrue(row.getRecommendationScore()>=0);assertFalse(row.getReasons().isEmpty());
                var saved=db.queryForMap("select status,stock_quantity,price from product where product_id=?",row.getProductId());
                assertEquals("ON_SALE",saved.get("status"));assertTrue(((Number)saved.get("stock_quantity")).intValue()>0);assertEquals(row.getPrice(),((Number)saved.get("price")).intValue());
                if(decaf!=null)assertEquals(decaf,row.isDecaf());
                if(i>0){var prev=rows.get(i-1);assertTrue(prev.getRecommendationScore()>row.getRecommendationScore()||prev.getRecommendationScore()==row.getRecommendationScore()&&(prev.getPrice()<row.getPrice()||prev.getPrice()==row.getPrice()&&prev.getProductId()<row.getProductId()));}
            }output.add(Map.of("input",input,"result",rows));
        }report.put("golden",output);assertEquals(before,balances());
    }
    @Test void case2CartConcurrency() throws Exception {
        clearCart(member);db.update("delete from cart where member_id=?",member);
        measure("first-creation-20",()->{try{return parallel(20,()->carts.getCart(member));}catch(Exception e){throw new RuntimeException(e);}});
        assertEquals(1,id("select count(*) from cart where member_id="+member));
        measure("existing-cart-20",()->{try{return parallel(20,()->carts.getCart(member));}catch(Exception e){throw new RuntimeException(e);}});
        carts.getCart(other);assertNotEquals(id("select cart_id from cart where member_id="+member),id("select cart_id from cart where member_id="+other));
        db.update("update product set stock_quantity=100 where product_id=?",product);add(member,product,1,"WHOLE_BEAN");
        int stock=db.queryForObject("select stock_quantity from product where product_id=?",Integer.class,product);
        try(var pool=Executors.newFixedThreadPool(21)){
            CountDownLatch start=new CountDownLatch(1);var reads=new ArrayList<Future<?>>();
            for(int i=0;i<20;i++)reads.add(pool.submit(()->{start.await();var c=carts.getCart(member);assertTrue(c.getTotalQuantity()==0||c.getTotalQuantity()==1);return null;}));
            var write=pool.submit(()->{start.await();return create(member,address);});start.countDown();assertNotNull(write.get(90,TimeUnit.SECONDS));for(var f:reads)f.get(90,TimeUnit.SECONDS);
        }assertEquals(stock-1,db.queryForObject("select stock_quantity from product where product_id=?",Integer.class,product));
    }
    @Test void case3StatisticsRangeAndRollback() throws Exception {
        List<Long> boundaryOrders=new ArrayList<>();
        for(Instant at:List.of(Instant.parse("2026-06-30T14:59:59.999999Z"),Instant.parse("2026-06-30T15:00:00Z"))) {
            clearCart(member);db.update("update product set stock_quantity=100 where product_id=?",product);add(member,product,1,"WHOLE_BEAN");
            var order=create(member,address);pg("POST",Map.of("approvedAt",at.toString()));
            var result=payments.confirm(member,dto(Map.of("paymentKey","perf-boundary-"+order.getOrderId(),"tossOrderId",order.getTossOrderId(),"amount",order.getTotalPrice()),PaymentDto.ConfirmRequest.class));
            assertEquals(200,result.status());boundaryOrders.add(order.getOrderId());
            assertEquals(at,db.queryForObject("select paid_at from payment where order_id=?",java.time.OffsetDateTime.class,order.getOrderId()).toInstant());
        }
        for(int days:List.of(1,30,366)) {
            LocalDate from=END.minusDays(days-1);measure("aggregate-"+days,()->sales.aggregateSalesRange(from,END));
            var expected=db.queryForList("select (p.paid_at at time zone 'Asia/Seoul')::date d,count(*) n,coalesce(sum(o.total_price),0) amount from payment p join orders o on o.order_id=p.order_id where p.status='SUCCESS' and p.paid_at>=? and p.paid_at<? group by d",Timestamp.from(from.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()),Timestamp.from(END.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()));
            for(var row:expected){var actual=db.queryForMap("select order_count,payment_amount from sales_statistics where stat_date=?",row.get("d"));assertEquals(((Number)row.get("n")).longValue(),((Number)actual.get("order_count")).longValue());assertEquals(((Number)row.get("amount")).longValue(),((Number)actual.get("payment_amount")).longValue());}
        }
        // Make both rows dirty so the second date really fails after the first date was written.
        db.update("update sales_statistics set payment_amount=payment_amount+1 where stat_date between ? and ?",END.minusDays(2),END);
        var before=statsSnapshot();db.execute("alter table sales_statistics add constraint perf_phase2_fail check (stat_date <> DATE '2026-06-29') not valid");
        try{assertThrows(RuntimeException.class,()->sales.aggregateSalesRange(END.minusDays(2),END));assertEquals(before,statsSnapshot());}finally{db.execute("alter table sales_statistics drop constraint perf_phase2_fail");}
        sales.aggregateDailySales(END);long previous=id("select payment_amount from sales_statistics where stat_date='2026-06-30'");
        long refunded=id("select total_price from orders where order_id="+boundaryOrders.getFirst());
        assertEquals(200,payments.cancel(member,boundaryOrders.getFirst(),false).status());sales.aggregateDailySales(END);
        assertEquals(previous-refunded,id("select payment_amount from sales_statistics where stat_date='2026-06-30'"));
        LocalDate empty=LocalDate.of(2040,2,1);sales.aggregateDailySales(empty);assertEquals(0,id("select order_count from sales_statistics where stat_date='2040-02-01'"));
        // Both payment approval and refund request this same date lock; time its contended path.
        try(var pool=Executors.newFixedThreadPool(2)){
            CountDownLatch held=new CountDownLatch(1);var hold=pool.submit(()->tx(()->{dateLock.acquire(END);held.countDown();try{Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return null;}));
            assertTrue(held.await(5,TimeUnit.SECONDS));long start=System.nanoTime();rebuild.request(END.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
            report.put("paymentDateLockWaitNanos",System.nanoTime()-start);hold.get();
        }
    }
    @Test @Timeout(300) void case4SchedulerInterference() throws Exception {
        assertEquals("true",System.getenv("PERF_WORKERS"));pg("DELETE",null);pg("POST",Map.of("lookupDelayMillis",45000));
        List<Long> opIds=new ArrayList<>();
        for(int i=0;i<3;i++) {
            long who=id("select member_id from member where email='loadtest-user-00000"+(i+1)+"@coffeeprod.local'");clearCart(who);db.update("update product set stock_quantity=100 where product_id=?",product);add(who,product,1,"WHOLE_BEAN");
            long a=id("select address_id from address where member_id="+who+" and is_default");var order=create(who,a);String key="perf-scheduler-"+i;
            var request=HttpRequest.newBuilder(URI.create(System.getenv("PERF_PG_URL")+"/v1/payments/confirm")).header("Idempotency-Key",order.getTossOrderId()).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("paymentKey",key,"orderId",order.getTossOrderId(),"amount",order.getTotalPrice())))).build();
            assertEquals(200,HTTP.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
            long op=tx(()->{var entity=orderRepository.findById(order.getOrderId()).orElseThrow();var operation=new PaymentOperation(entity,PaymentOperation.Type.CONFIRM,key,"USER",who);operation.claim(Instant.now(),Duration.ofSeconds(120),false);operation.unknown("PERF_LOST_RESPONSE");return operations.saveAndFlush(operation).getId();});
            db.update("update payment_operation set next_attempt_at=null where id=?",op);opIds.add(op);
        }
        pg("POST",Map.of("lookupDelayMillis",45000));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(240);
        while(pg("GET",null).path("lookups").asLong()==0 && System.nanoTime()<deadline)Thread.sleep(200);
        assertTrue(pg("GET",null).path("lookups").asLong()>0);long queued=System.nanoTime();rebuild.request(END.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        while(id("select count(*) from sales_rebuild_request where stat_date='2026-06-30'")>0 && System.nanoTime()<deadline)Thread.sleep(200);
        report.put("statisticsQueueNanos",System.nanoTime()-queued);assertEquals(0,id("select count(*) from sales_rebuild_request where stat_date='2026-06-30'"));
        for(Long op:opIds){while(!"SUCCEEDED".equals(db.queryForObject("select status from payment_operation where id=?",String.class,op))&&System.nanoTime()<deadline)Thread.sleep(200);assertEquals("SUCCEEDED",db.queryForObject("select status from payment_operation where id=?",String.class,op));}
        assertEquals(3,pg("GET",null).path("posts").asLong());report.put("mockCalls",pg("GET",null));
    }
    @Test void case5OrderSummaries() {
        var before=balances();var observations=new ArrayList<Object>();
        for(int size:List.of(10,50)){
            var page=PageRequest.of(0,size);var own=measure("member-page-"+size,()->orders.getMyOrders(member,page));var all=measure("admin-page-"+size,()->orders.getAllOrders(page));
            assertEquals(id("select count(*) from orders where member_id="+member),own.getTotalElements());assertEquals(id("select count(*) from orders"),all.getTotalElements());
            Set<Long> seen=new HashSet<>();for(var row:own){assertTrue(seen.add(row.getOrderId()));assertEquals(member,id("select member_id from orders where order_id="+row.getOrderId()));
                assertEquals(id("select count(*) from order_item where order_id="+row.getOrderId()),row.getItemCount());
                String first=db.queryForObject("select p.name from order_item oi join product p on p.product_id=oi.product_id where oi.order_id=? order by oi.order_item_id limit 1",String.class,row.getOrderId());
                observations.add(Map.of("orderId",row.getOrderId(),"actual",row.getFirstProductName(),"minItemIdProduct",first,"matches",first.equals(row.getFirstProductName())));
            }for(var row:all)assertEquals(id("select member_id from orders where order_id="+row.getOrderId()),row.getMemberId());
        }report.put("representativeObservations",observations);assertEquals(before,balances());
    }
    @Test void case6OrderWrites() throws Exception {
        var ids=saleProducts(20);assertEquals(20,ids.size());
        for(int size:List.of(1,5,20)){
            clearCart(member);for(Long p:ids.subList(0,size)){db.update("update product set stock_quantity=100 where product_id=?",p);add(member,p,2,"WHOLE_BEAN");}
            add(member,ids.getFirst(),1,"DRIP");var before=new HashMap<Long,Integer>();for(Long p:ids.subList(0,size))before.put(p,db.queryForObject("select stock_quantity from product where product_id=?",Integer.class,p));
            var order=measure("create-"+size,()->create(member,address));assertEquals(size+1,order.getOrderItems().size());
            for(Long p:ids.subList(0,size))assertEquals(before.get(p)-(p.equals(ids.getFirst())?3:2),db.queryForObject("select stock_quantity from product where product_id=?",Integer.class,p));
        }
        clearCart(member);add(member,product,100000,"WHOLE_BEAN");var before=balances();assertThrows(RuntimeException.class,()->create(member,address));assertEquals(before,balances());
        clearCart(member);clearCart(other);db.update("update product set stock_quantity=10 where product_id=?",product);
        long mileage=id("select mileage from member where member_id="+member);add(member,product,2,"WHOLE_BEAN");var paid=create(member,address);
        assertEquals(200,payments.confirm(member,dto(Map.of("paymentKey","perf-stock-race","tossOrderId",paid.getTossOrderId(),"amount",paid.getTotalPrice()),PaymentDto.ConfirmRequest.class)).status());
        add(other,product,3,"DRIP");long otherAddress=id("select address_id from address where member_id="+other+" and is_default");
        try(var pool=Executors.newFixedThreadPool(3)){
            CountDownLatch start=new CountDownLatch(1);
            var refund=pool.submit(()->{start.await();return payments.cancel(member,paid.getOrderId(),false);});
            var intake=pool.submit(()->{start.await();return products.addStock(product,dto(Map.of("quantity",5),com.back.coffeeprod.domain.product.dto.ProductDto.StockRequest.class));});
            var purchase=pool.submit(()->{start.await();return create(other,otherAddress);});start.countDown();
            assertEquals(200,refund.get(60,TimeUnit.SECONDS).status());intake.get(60,TimeUnit.SECONDS);assertNotNull(purchase.get(60,TimeUnit.SECONDS));
        }
        assertEquals(12,id("select stock_quantity from product where product_id="+product));assertEquals(mileage,id("select mileage from member where member_id="+member));
    }
    @Test void case7JwtParseCountsAndAuth() throws Exception {
        String token=jwt.generateAccessToken(member,Role.USER);AtomicInteger builds=new AtomicInteger(),parses=new AtomicInteger();
        try(var mocked=mockStatic(Jwts.class,CALLS_REAL_METHODS)){
            mocked.when(Jwts::parser).thenAnswer(inv->{var builder=spy((JwtParserBuilder)inv.callRealMethod());doAnswer(call->{builds.incrementAndGet();var parser=spy((JwtParser)call.callRealMethod());doAnswer(p->{parses.incrementAndGet();return p.callRealMethod();}).when(parser).parseSignedClaims(anyString());return parser;}).when(builder).build();return builder;});
            // Observe construction as well as requests, including a parser cached by JwtUtil.
            var measuredJwt=new JwtUtil(System.getenv("JWT_SECRET_KEY"),
                (Long)org.springframework.test.util.ReflectionTestUtils.getField(jwt,"accessExpiration"),
                (Long)org.springframework.test.util.ReflectionTestUtils.getField(jwt,"refreshExpiration"));
            report.put("parserBuildsInitialization",builds.get());builds.set(0);parses.set(0);
            measure("authenticated-filter",()->{try{SecurityContextHolder.clearContext();var request=new MockHttpServletRequest("GET","/api/v1/carts");request.addHeader("Authorization","Bearer "+token);var response=new MockHttpServletResponse();new JwtAuthenticationFilter(measuredJwt,userDetails,entry).doFilter(request,response,new MockFilterChain());assertNotNull(SecurityContextHolder.getContext().getAuthentication());return null;}catch(Exception e){throw new RuntimeException(e);}finally{SecurityContextHolder.clearContext();}});
        }report.put("parserBuilds",builds.get());report.put("parseSignedClaims",parses.get());assertTrue(parses.get()>=1);
        http("GET","/api/v1/carts",null,"invalid",401);http("GET","/api/v1/carts",null,null,401);
        var expired=new JwtUtil(System.getenv("JWT_SECRET_KEY"),-1000,1209600000);
        http("GET","/api/v1/carts",null,expired.generateAccessToken(member,Role.USER),401);
        int dot=token.lastIndexOf('.')+1;String tampered=token.substring(0,dot)+(token.charAt(dot)=='A'?'B':'A')+token.substring(dot+1);
        http("GET","/api/v1/carts",null,tampered,401);
        http("GET","/api/v1/carts",null,token,200);
        var login=http("POST","/api/v1/auth/login",Map.of("email","loadtest-user-000001@coffeeprod.local","password","password"),null,200);
        String refresh=login.path("refreshToken").asText();assertFalse(refresh.isBlank());
        var rotated=http("POST","/api/v1/auth/reissue",Map.of("refreshToken",refresh),null,200);assertNotEquals(refresh,rotated.path("refreshToken").asText());
        http("POST","/api/v1/auth/reissue",Map.of("refreshToken",refresh),null,401);
        String legacy=Jwts.builder().subject(Long.toString(member)).issuedAt(new java.util.Date()).expiration(new java.util.Date(System.currentTimeMillis()+60000))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(System.getenv("JWT_SECRET_KEY").getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        refreshTokens.save(member,legacy);var compatible=http("POST","/api/v1/auth/reissue",Map.of("refreshToken",legacy),null,200);
        assertFalse(compatible.path("refreshToken").asText().isBlank());assertFalse(legacy.equals(compatible.path("refreshToken").asText()));
        db.update("update member set status='SUSPENDED' where member_id=?",member);http("GET","/api/v1/carts",null,token,403);
        db.update("update member set status='WITHDRAWN' where member_id=?",member);http("GET","/api/v1/carts",null,token,401);
    }
    @Test void case8SearchAndPlans() {
        JdbcCapture.start();
        var page=PageRequest.of(0,10);measure("review",()->reviews.getReviews(product,page));measure("qna",()->qnas.getProductQnas(product,page));measure("qna-admin",()->qnas.getAdminQnas(null,page));
        measure("sales-read",()->sales.getSalesStatistics(END.minusDays(29),END,SalesStatisticsUnit.DAILY));measure("admin-orders",()->orders.getAllOrders(page));
        var plans=new LinkedHashMap<String,Object>();
        for(String keyword:Arrays.asList(null,"에티오피아","NO_MATCH_PHASE2")) {
            var found=measure("product-keyword-"+keyword,()->products.getProducts(null,null,null,null,null,null,keyword,page));
            long count=keyword==null?id("select count(*) from product where status='ON_SALE'"):db.queryForObject("select count(*) from product where status='ON_SALE' and name like ?",Long.class,"%"+keyword+"%");
            assertEquals(count,found.getTotalElements());
        }
        var actualQueries=JdbcCapture.stop();
        report.put("actualSqlPlans",JdbcCapture.explain(db,actualQueries));
        for(String filter:List.of("", " where status='ON_SALE'", " where name like '%에티오피아%'", " where status='ON_SALE' and name like '%에티오피아%'", " where name like '%NO_MATCH_PHASE2%'")){
            String query="select product_id from product"+filter+" order by product_id limit 10";
            plans.put(query,db.queryForObject("explain (analyze,buffers,format json) "+query,String.class));
        }
        for(String query:List.of("select * from reviews where product_id="+product+" order by created_at desc limit 10","select * from product_qna where product_id="+product+" order by created_at desc limit 10","select order_id from orders order by order_date desc limit 50","select * from payment where status='SUCCESS' and paid_at >= timestamptz '2026-06-01T00:00:00+09:00' and paid_at < timestamptz '2026-07-01T00:00:00+09:00'"))
            plans.put(query,db.queryForObject("explain (analyze,buffers,format json) "+query,String.class));
        report.put("plans",plans);report.put("indexes",db.queryForList("select tablename,indexname,indexdef from pg_indexes where schemaname='public' order by tablename,indexname"));
        report.put("capturedServiceSql",samples.stream().map(s->s.get("sql")).toList());
        var before=balances();String sql="""
            with p as (
              insert into product(category_id,coffee_profile_id,sku,weight_grams,name,price,stock_quantity,roast_level,description,image_url,status,created_at,updated_at)
              select category_id,coffee_profile_id,?,weight_grams,'PERF2 WRITE',price,100,roast_level,description,image_url,'ON_SALE',now(),now()
              from product where product_id=? returning product_id,price
            ), r as (
              insert into reviews(member_id,product_id,rating,content,created_at,updated_at)
              select ?,product_id,5,'PERF2 WRITE',now(),now() from p
            ), q as (
              insert into product_qna(member_id,product_id,title,question,status,created_at,updated_at)
              select ?,product_id,'PERF2 WRITE','Synthetic question','WAITING',now(),now() from p
            ), o as (
              insert into orders(member_id,toss_order_id,order_date,status,used_mileage,delivery_address,product_total_price,delivery_fee,earned_mileage,total_price,created_at,updated_at)
              select ?,?,now(),'PAID',0,'PERF2 SYNTHETIC',price,3000,0,price+3000,now(),now() from p returning order_id
            ), oi as (
              insert into order_item(order_id,product_id,order_price,quantity,grind_type)
              select o.order_id,p.product_id,p.price,1,'WHOLE_BEAN' from o,p
            ) insert into payment(order_id,pg_provider,payment_key,pay_method,status,paid_at)
              select order_id,'TOSS',?,'카드','SUCCESS',now() from o
            """;
        measure("rollback-insert-chain-100",()->new TransactionTemplate(transactions).execute(status->{
            for(int i=0;i<100;i++)db.update(sql,"PERF2-WRITE-"+i,product,member,member,member,"PERF2-WRITE-"+i,"perf-write-"+i);
            status.setRollbackOnly();return null;
        }));report.put("jdbcInsertChains",100);report.put("writeCostScope","product/review/qna/order/item/payment combined insert cost; rolled back; no per-index attribution");
        assertEquals(before,balances());
    }
}

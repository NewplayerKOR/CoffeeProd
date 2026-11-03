package com.back.coffeeprod.performance;

import com.sun.net.httpserver.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Independent disposable provider, with bounded lookup delay and full-cancel idempotency. */
public final class PerfPgServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final JsonMapper json=JsonMapper.builder().build();
    private final Map<String,Map<String,Object>> payments=new ConcurrentHashMap<>();
    private final Map<String,String> operations=new ConcurrentHashMap<>();
    private final AtomicLong lookups=new AtomicLong(), posts=new AtomicLong();
    private final AtomicLong postCalls=new AtomicLong();
    private volatile long delayMillis;
    private volatile Instant approvedAt;
    public PerfPgServer(int port) throws Exception {
        this(port,"127.0.0.1");
    }
    private PerfPgServer(int port,String host) throws Exception {
        server=HttpServer.create(new InetSocketAddress(host,port),0);
        server.setExecutor(executor); server.createContext("/",this::handle); server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    public long lookups() { return lookups.get(); }
    public long posts() { return posts.get(); }
    public long postCalls() { return postCalls.get(); }
    public void delay(long millis) {
        if (millis<0 || millis>45000) throw new IllegalArgumentException("Delay must be 0..45000 ms");
        delayMillis=millis;
    }
    public void reset() { payments.clear(); operations.clear(); lookups.set(0); posts.set(0); postCalls.set(0); delayMillis=0; approvedAt=null; }
    private void handle(HttpExchange x) {
        try {
            String path=x.getRequestURI().getPath(), method=x.getRequestMethod();
            if (path.equals("/health")) { reply(x,200,Map.of("status","UP")); return; }
            if (path.equals("/__control")) {
                if (method.equals("DELETE")) reset();
                if (method.equals("POST")) {
                    var input=json.readTree(x.getRequestBody().readAllBytes()); delay(input.path("lookupDelayMillis").asLong());
                    if(input.has("approvedAt")) approvedAt=Instant.parse(input.path("approvedAt").asText());
                }
                reply(x,200,Map.of("lookups",lookups(),"posts",postCalls(),"uniquePosts",posts(),"lookupDelayMillis",delayMillis)); return;
            }
            if (method.equals("GET")) {
                lookups.incrementAndGet(); Thread.sleep(delayMillis);
                String id=path.substring(path.lastIndexOf('/')+1);
                synchronized(payments) {
                    Map<String,Object> payment=path.startsWith("/v1/payments/orders/")
                        ? payments.values().stream().filter(p->id.equals(p.get("orderId"))).findFirst().orElse(null):payments.get(id);
                    reply(x,payment==null?404:200,payment==null?Map.of("code","NOT_FOUND_PAYMENT"):new LinkedHashMap<>(payment)); return;
                }
            }
            if (!method.equals("POST")) { reply(x,405,Map.of()); return; }
            postCalls.incrementAndGet();
            String key=x.getRequestHeaders().getFirst("Idempotency-Key");
            String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if (key==null || key.isBlank()) { reply(x,400,Map.of("code","MISSING_IDEMPOTENCY")); return; }
            synchronized (payments) {
                String old=operations.putIfAbsent(key,path+body);
                if (old!=null && !old.equals(path+body)) { reply(x,409,Map.of("code","KEY_REUSED")); return; }
                if (old==null) posts.incrementAndGet();
                var input=json.readTree(body);
                if (path.equals("/v1/payments/confirm")) {
                    String paymentKey=input.path("paymentKey").asText();
                    var p=payments.computeIfAbsent(paymentKey,k->{
                        Map<String,Object> value=new LinkedHashMap<>(); value.put("paymentKey",k); value.put("orderId",input.path("orderId").asText());
                        value.put("status","DONE"); value.put("totalAmount",input.path("amount").asInt()); value.put("balanceAmount",input.path("amount").asInt());
                        value.put("method","카드"); value.put("card",Map.of("company","PERF")); value.put("approvedAt",(approvedAt==null?Instant.now():approvedAt).toString()); value.put("cancels",List.of()); return value;
                    }); reply(x,200,p);
                } else if (path.endsWith("/cancel")) {
                    String paymentKey=path.substring("/v1/payments/".length(),path.length()-"/cancel".length());
                    var p=payments.get(paymentKey);
                    if(p==null) { reply(x,404,Map.of("code","NOT_FOUND_PAYMENT")); return; }
                    if(!p.get("status").equals("CANCELED")) {
                        p.put("status","CANCELED"); p.put("balanceAmount",0);
                        p.put("cancels",List.of(Map.of("transactionKey",UUID.randomUUID().toString(),"cancelAmount",p.get("totalAmount"),"cancelStatus","DONE","canceledAt",Instant.now().toString())));
                    } reply(x,200,p);
                } else reply(x,404,Map.of());
            }
        } catch (Exception e) { try { reply(x,500,Map.of("code","MOCK_ERROR")); } catch(Exception ignored) { x.close(); } }
    }
    private void reply(HttpExchange x,int code,Object body) throws Exception {
        byte[] data=json.writeValueAsBytes(body); x.getResponseHeaders().set("Content-Type","application/json");
        x.sendResponseHeaders(code,data.length); x.getResponseBody().write(data); x.close();
    }
    @Override public void close() { server.stop(0); executor.close(); }
    public static void main(String[] args) throws Exception {
        var pg=new PerfPgServer(Integer.parseInt(System.getenv().getOrDefault("PERF_MOCK_PORT","9080")),System.getenv().getOrDefault("PERF_MOCK_BIND_IP","0.0.0.0")); Runtime.getRuntime().addShutdownHook(new Thread(pg::close));
        new CountDownLatch(1).await();
    }
}

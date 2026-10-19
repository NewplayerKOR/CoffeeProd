package com.back.coffeeprod.domain.payment.service;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Independent provider state survives application JVM termination; synthetic data only. */
final class QaPgServer implements AutoCloseable {
    enum Fault { NONE, REJECT, FIVE_HUNDRED, CLOSE, EMPTY, MALFORMED, MISMATCH, NOT_FOUND, TIMEOUT, LOST_SUCCESS }
    record Call(String method, String path, String key, String body) {}
    final HttpServer server;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final JsonMapper json = JsonMapper.builder().build();
    final Map<String,Map<String,Object>> state = new ConcurrentHashMap<>();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final Map<String,String> idempotencyBodies = new ConcurrentHashMap<>();
    volatile Fault confirmFault = Fault.NONE, cancelFault = Fault.NONE, lookupFault = Fault.NONE;
    volatile String method = "카드";
    volatile boolean card = true, partial;
    volatile Instant approvedAt = Instant.parse("2044-01-31T14:59:59Z");
    volatile Instant canceledAt = Instant.parse("2044-02-01T01:00:00Z");
    volatile CountDownLatch entered, release;

    QaPgServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    String verb = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
                    String body = new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                    String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    calls.add(new Call(verb,path,key,body));
                    boolean confirm = path.equals("/v1/payments/confirm"), cancel = path.endsWith("/cancel");
                    Fault fault = verb.equals("GET") ? lookupFault : confirm ? confirmFault : cancelFault;
                    if (verb.equals("POST")) {
                        String previous = idempotencyBodies.putIfAbsent(key, path+body);
                        if (previous != null && !previous.equals(path+body)) throw new AssertionError("idempotency changed");
                    }
                    if (entered != null && verb.equals("POST") && cancel) {
                        entered.countDown();
                        if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("provider release timeout");
                    }
                    int status = 200;
                    Object result;
                    if (fault == Fault.REJECT) {
                        status=400; result=Map.of("code",confirm ? "REJECT_CARD_COMPANY" : "REFUND_REJECTED");
                    } else if (fault == Fault.FIVE_HUNDRED) { status=503; result=Map.of("code","TEMPORARY"); }
                    else if (fault == Fault.NOT_FOUND) { status=404; result=Map.of("code","NOT_FOUND_PAYMENT"); }
                    else if (fault == Fault.CLOSE) { exchange.close(); return; }
                    else {
                        Map<String,Object> payment;
                        if (confirm) {
                            var request=json.readTree(body);
                            String paymentKey=request.path("paymentKey").asText();
                            payment=new LinkedHashMap<>();
                            payment.put("paymentKey",paymentKey);payment.put("orderId",request.path("orderId").asText());
                            payment.put("totalAmount",request.path("amount").asInt());payment.put("balanceAmount",request.path("amount").asInt());
                            payment.put("status","DONE");payment.put("method",method);payment.put("approvedAt",approvedAt.toString());
                            if(card) payment.put("card",Map.of("amount",request.path("amount").asInt()));
                            else payment.put("easyPay",Map.of("provider","테스트"));
                            state.putIfAbsent(paymentKey,payment);payment=state.get(paymentKey);
                        } else if (cancel) {
                            String paymentKey=path.substring("/v1/payments/".length(),path.length()-"/cancel".length());
                            payment=new LinkedHashMap<>(Objects.requireNonNull(state.get(paymentKey)));
                            payment.put("status",partial ? "PARTIAL_CANCELED" : "CANCELED");
                            payment.put("balanceAmount",partial ? 1 : 0);
                            payment.put("cancels",List.of(Map.of("transactionKey","qa-tx-"+paymentKey,"cancelAmount",payment.get("totalAmount"),
                                    "cancelStatus","DONE","canceledAt",canceledAt.toString())));
                            state.put(paymentKey,payment);
                        } else if (path.startsWith("/v1/payments/orders/")) {
                            String order=path.substring("/v1/payments/orders/".length());
                            payment=state.values().stream().filter(p->order.equals(p.get("orderId"))).findFirst().orElse(null);
                        } else payment=state.get(path.substring("/v1/payments/".length()));
                        result=payment;
                        if(payment==null) {status=404;result=Map.of("code","NOT_FOUND_PAYMENT");}
                        if(fault==Fault.MISMATCH && payment!=null) {var invalid=new LinkedHashMap<>(payment);invalid.put("totalAmount",-1);result=invalid;}
                        if(fault==Fault.LOST_SUCCESS) {exchange.close();return;}
                        if(fault==Fault.TIMEOUT) Thread.sleep(1100);
                    }
                    byte[] bytes=(fault==Fault.EMPTY ? "" : fault==Fault.MALFORMED ? "invalid-json" : json.writeValueAsString(result)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json;charset=UTF-8");
                    exchange.sendResponseHeaders(status,bytes.length==0 ? -1 : bytes.length);
                    if(bytes.length>0) exchange.getResponseBody().write(bytes);
                } catch (InterruptedException e) {Thread.currentThread().interrupt();}
                finally {exchange.close();}
            });
            server.start();
        } catch (java.io.IOException e) {throw new java.io.UncheckedIOException(e);}
    }
    String url() {return "http://127.0.0.1:"+server.getAddress().getPort();}
    long posts(String suffix) {return calls.stream().filter(c->c.method().equals("POST")&&c.path().endsWith(suffix)).count();}
    void reset() {
        state.clear();calls.clear();idempotencyBodies.clear();confirmFault=cancelFault=lookupFault=Fault.NONE;
        method="카드";card=true;partial=false;entered=null;release=null;
        approvedAt=Instant.parse("2044-01-31T14:59:59Z");canceledAt=Instant.parse("2044-02-01T01:00:00Z");
    }
    @Override public void close() {server.stop(0);executor.shutdownNow();}
}

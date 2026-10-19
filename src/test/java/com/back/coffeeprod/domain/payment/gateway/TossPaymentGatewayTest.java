package com.back.coffeeprod.domain.payment.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import static org.junit.jupiter.api.Assertions.*;

class TossPaymentGatewayTest {
    HttpServer server;
    TossPaymentGateway gateway;
    record Reply(int status,String body) {}
    record Request(String method,String path,String idempotency,String authorization,String body) {}
    LinkedBlockingQueue<Reply> replies=new LinkedBlockingQueue<>();
    LinkedBlockingQueue<Request> requests=new LinkedBlockingQueue<>();
    static final String DONE="""
        {"paymentKey":"key","orderId":"COFFEE-order","status":"DONE","totalAmount":1000,
         "balanceAmount":1000,"method":"간편결제","approvedAt":"2026-10-19T00:01:00+09:00","card":{"amount":1000}}
        """;
    static final String CANCELED="""
        {"paymentKey":"key","orderId":"COFFEE-order","status":"CANCELED","totalAmount":1000,
         "balanceAmount":0,"method":"카드","approvedAt":"2026-10-19T00:01:00+09:00","card":{"amount":1000},
         "cancels":[{"transactionKey":"tx","cancelAmount":1000,"cancelStatus":"DONE","canceledAt":"2026-10-20T12:00:00+09:00"}]}
        """;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(),exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            var reply=replies.remove(); byte[] data=reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json;charset=UTF-8");
            exchange.sendResponseHeaders(reply.status(),data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        gateway=new TossPaymentGateway("test-only","http://127.0.0.1:"+server.getAddress().getPort(),
                new PaymentRecoveryProperties(Duration.ofSeconds(5),Duration.ofSeconds(60),Duration.ofSeconds(120)));
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void confirmLookupAndFullCancelUseStoredKeysAndNormalizeProviderSnapshot() {
        replies.add(new Reply(200,DONE)); var approved=gateway.confirm("key","COFFEE-order",1000,"confirm-key");
        assertTrue(approved.approved()); assertTrue(approved.cardBased());
        var confirm=requests.remove(); assertEquals("confirm-key",confirm.idempotency());
        assertEquals("Basic "+Base64.getEncoder().encodeToString("test-only:".getBytes(StandardCharsets.UTF_8)),confirm.authorization());
        assertTrue(confirm.body().contains("\"amount\":1000"));
        replies.add(new Reply(200,DONE)); gateway.lookupByPaymentKey("key");
        assertEquals("/v1/payments/key",requests.remove().path());
        replies.add(new Reply(200,DONE)); gateway.lookupByOrderId("COFFEE-order");
        assertEquals("/v1/payments/orders/COFFEE-order",requests.remove().path());
        replies.add(new Reply(200,CANCELED)); var canceled=gateway.cancelFull("key","사용자 주문 전체 취소","cancel-key");
        assertEquals("tx",canceled.fullCancellation().transactionKey());
        var request=requests.remove(); assertEquals("/v1/payments/key/cancel",request.path());
        assertEquals("cancel-key",request.idempotency()); assertTrue(request.body().contains("사용자 주문 전체 취소"));
        assertFalse(request.body().contains("cancelAmount"));
    }
    @Test void onlyExplicitFinalRejectionIsRejectedAndTemporaryOrUnknownErrorsStayUnknown() {
        replies.add(new Reply(403,"{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"declined\"}"));
        assertTrue(assertThrows(GatewayException.class,()->gateway.confirm("key","COFFEE-order",1000,"same")).isRejected());
        replies.add(new Reply(400,"{\"code\":\"ALREADY_PROCESSING_REQUEST\"}"));
        assertFalse(assertThrows(GatewayException.class,()->gateway.confirm("key","COFFEE-order",1000,"same")).isRejected());
        replies.add(new Reply(500,"{\"code\":\"REJECT_CARD_COMPANY\"}"));
        assertFalse(assertThrows(GatewayException.class,()->gateway.confirm("key","COFFEE-order",1000,"same")).isRejected());
        replies.add(new Reply(400,"{\"code\":\"REFUND_REJECTED\"}"));
        assertTrue(assertThrows(GatewayException.class,()->gateway.cancelFull("key","reason","same")).isRejected());
        replies.add(new Reply(400,"{\"code\":\"ALREADY_CANCELED_PAYMENT\"}"));
        assertFalse(assertThrows(GatewayException.class,()->gateway.cancelFull("key","reason","same")).isRejected());
    }
    @Test void missingFieldsMalformedBodyAndTemporaryLookup404CannotBecomeSuccess() {
        for(var body:List.of("{}","not-json",DONE.replace("\"totalAmount\":1000,",""))) {
            replies.add(new Reply(200,body));
            assertFalse(assertThrows(GatewayException.class,()->gateway.lookupByPaymentKey("key")).isRejected());
        }
        replies.add(new Reply(404,"{\"code\":\"NOT_FOUND_PAYMENT\"}"));
        assertFalse(assertThrows(GatewayException.class,()->gateway.lookupByOrderId("COFFEE-order")).isRejected());
    }
    @Test void partialOrProcessingCancellationDoesNotValidateAsFullRefund() {
        replies.add(new Reply(200,CANCELED.replace("\"cancelStatus\":\"DONE\"","\"cancelStatus\":\"IN_PROGRESS\"")));
        assertNull(gateway.lookupByPaymentKey("key").fullCancellation());
        replies.add(new Reply(200,CANCELED.replace("\"cancelAmount\":1000","\"cancelAmount\":900")));
        assertNull(gateway.lookupByPaymentKey("key").fullCancellation());
    }
    @Test void fakeReplaysOriginalPostResponseAndLookupReturnsCurrentState() {
        var fake=new FakePaymentGateway();
        var original=fake.confirm("key","order",1000,"confirm");
        var canceled=fake.cancelFull("key","reason","cancel");
        assertEquals(original,fake.confirm("key","order",1000,"confirm"));
        assertEquals(canceled,fake.cancelFull("key","reason","cancel"));
        assertEquals(canceled,fake.lookupByPaymentKey("key"));
        assertThrows(GatewayException.class,()->fake.confirm("other","order",1000,"confirm"));
    }
}

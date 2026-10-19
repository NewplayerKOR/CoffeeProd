package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.domain.payment.entity.*;
import com.back.coffeeprod.domain.payment.gateway.*;
import com.back.coffeeprod.domain.payment.dto.PaymentDto;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in human test session; not a JUnit test or production endpoint.
 * SDK contract: https://docs.tosspayments.com/sdk/v2/js/payment */
public final class QaTossTestSession {
    @TestConfiguration public static class Config {
        @Bean @Primary PaymentGateway realTestGateway() {
            return new TossPaymentGateway(System.getenv("QA_TOSS_TEST_SECRET_KEY"),"https://api.tosspayments.com",
                    new PaymentRecoveryProperties(Duration.ofSeconds(5),Duration.ofSeconds(60),Duration.ofSeconds(120)));
        }
    }
    static void requireTestKeys(String client,String secret) {
        if(client==null||secret==null||!client.startsWith("test_ck_")||!secret.startsWith("test_sk_"))
            throw new IllegalArgumentException("Toss payment-window test_ck/test_sk pair required; values are never printed");
    }
    public static void main(String[] args) {
        try{run();System.exit(0);}
        catch(Throwable failure){System.err.println("Toss test helper failed: "+failure.getClass().getSimpleName()+" (values redacted)");System.exit(1);}
    }
    static void run() throws Exception {
        requireTestKeys(System.getenv("QA_TOSS_TEST_CLIENT_KEY"),System.getenv("QA_TOSS_TEST_SECRET_KEY"));
        if(!"true".equals(System.getenv("QA_TASK6_ALLOW_TOSS_TEST")))throw new IllegalArgumentException("Explicit opt-in required");
        String url=System.getenv("QA_TASK6_TOSS_PG_URL");
        if(!"jdbc:postgresql://127.0.0.1:55487/qa6_toss".equals(url))throw new IllegalArgumentException("Dedicated Toss QA DB required");
        try(var context=QaRestartChild.open(url,"validate",Config.class)) {
            var support=new QaPaymentSupport(){};context.getAutowireCapableBeanFactory().autowireBean(support);
            var gateway=context.getBean(PaymentGateway.class);
            Map<String,QaPaymentSupport.Fixture> fixtures=Map.of("card",support.fixture(),"easy",support.fixture());
            Set<String> approved=new HashSet<>(),completed=new HashSet<>();Map<String,String> keys=new HashMap<>();
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            String capability="/qa-"+UUID.randomUUID(),origin="http://127.0.0.1:"+server.getAddress().getPort(),base=origin+capability;
            Path results=Path.of("build/qa-task6/toss-test");Files.createDirectories(results);
            server.createContext(capability,exchange->{
                String relative=exchange.getRequestURI().getPath().substring(capability.length());int status=200;String response="";
                try {
                    if(relative.equals("/success/card")||relative.equals("/success/easy")) {
                        String kind=relative.substring(relative.lastIndexOf('/')+1);var f=fixtures.get(kind);var query=query(exchange.getRequestURI().getRawQuery());
                        assertEquals(f.toss(),query.get("orderId"));assertEquals(f.amount(),Integer.parseInt(query.get("amount")));
                        String key=query.get("paymentKey");assertNotNull(key);
                        var r=new PaymentDto.ConfirmRequest();ReflectionTestUtils.setField(r,"paymentKey",key);ReflectionTestUtils.setField(r,"tossOrderId",f.toss());ReflectionTestUtils.setField(r,"amount",f.amount());
                        var confirmation=support.workflow.confirm(f.member(),r);assertEquals(200,confirmation.status());
                        var pg=gateway.lookupByPaymentKey(key);assertTrue(pg.matches(key,f.toss(),f.amount()));assertTrue(pg.approved());assertTrue(pg.cardBased());
                        assertEquals(kind.equals("card")?"카드":"간편결제",pg.method());
                        keys.put(kind,key);approved.add(kind);support.resources(f,9,797,0);
                        exchange.getResponseHeaders().set("Location",base+"/");status=303;
                    } else if(relative.equals("/cancel/card")||relative.equals("/cancel/easy")) {
                        if(!exchange.getRequestMethod().equals("POST")||!origin.equals(exchange.getRequestHeaders().getFirst("Origin")))throw new IllegalArgumentException("Local POST required");
                        String kind=relative.substring(relative.lastIndexOf('/')+1);var f=fixtures.get(kind);assertTrue(approved.contains(kind));
                        var cancellation=support.workflow.cancel(f.member(),f.order(),kind.equals("easy"));assertEquals(200,cancellation.status());
                        var pg=gateway.lookupByPaymentKey(keys.get(kind));assertTrue(pg.matches(keys.get(kind),f.toss(),f.amount()));assertNotNull(pg.fullCancellation());
                        var p=support.payments.findByOrdersId(f.order()).orElseThrow();assertEquals(PaymentStatus.REFUNDED,p.getStatus());support.resources(f,10,1000,0);
                        var operation=support.operation(f,PaymentOperation.Type.CANCEL_FULL);assertEquals(pg.fullCancellation().transactionKey(),operation.getPgTransactionKey());
                        assertEquals(pg.fullCancellation().canceledAt(),p.getRefundedAt());assertEquals(pg.approvedAt(),p.getPaidAt());
                        support.workflow.cancel(f.member(),f.order(),false);support.resources(f,10,1000,0);
                        completed.add(kind);
                        Files.writeString(results.resolve(kind+".json"),QaPaymentSupport.JSON.writeValueAsString(Map.of("case",kind,"result","PASS","amount",f.amount(),
                                "identityMatch",true,"transactionMatch",true,"paidAt",p.getPaidAt().toString(),"refundedAt",p.getRefundedAt().toString(),"resourcesMatch",true)));
                        response="전체 취소·PG 조회·DB·재고·마일리지 대조 통과";
                    } else if(relative.equals("/")||relative.isEmpty()) {
                        StringBuilder html=new StringBuilder("<!doctype html><html lang=ko><meta charset=utf-8><title>QA TASK 6 Toss 테스트</title><script src='https://js.tosspayments.com/v2/standard'></script><h1>QA TASK 6 테스트 결제</h1><p>카드와 카드 기반 간편결제를 각각 진행하세요. 승인 후 전체 취소 버튼으로 검증합니다.</p>");
                        for(String kind:List.of("card","easy")) {
                            var f=fixtures.get(kind);String label=kind.equals("card")?"카드 · 사용자 취소":"카드 간편결제 · 관리자 취소";
                            var payload=new LinkedHashMap<String,Object>();payload.put("method","CARD");payload.put("amount",Map.of("currency","KRW","value",f.amount()));
                            payload.put("orderId",f.toss());payload.put("orderName","CoffeeProd QA TASK 6");payload.put("successUrl",base+"/success/"+kind);payload.put("failUrl",base+"/fail");
                            if(kind.equals("easy"))payload.put("card",Map.of("flowMode","DIRECT","easyPay","TOSSPAY"));
                            html.append("<section><h2>").append(label).append("</h2><p>").append(completed.contains(kind)?"검증 통과":approved.contains(kind)?"승인 완료":"인증 대기").append("</p>");
                            if(!approved.contains(kind))html.append("<button onclick='pay").append(kind).append("()'>테스트 결제</button>");
                            if(approved.contains(kind)&&!completed.contains(kind))html.append("<button onclick=\"fetch('").append(base).append("/cancel/").append(kind).append("',{method:'POST'}).then(r=>r.text()).then(t=>{document.getElementById('result').textContent=t})\">전체 취소 검증</button>");
                            html.append("</section><script>async function pay").append(kind).append("(){const p=TossPayments(")
                                    .append(QaPaymentSupport.JSON.writeValueAsString(System.getenv("QA_TOSS_TEST_CLIENT_KEY"))).append(").payment({customerKey:'qa-").append(UUID.randomUUID()).append("'});await p.requestPayment(")
                                    .append(QaPaymentSupport.JSON.writeValueAsString(payload)).append(");}</script>");
                        }
                        response=html.append("<pre id=result></pre></html>").toString();
                    } else {status=400;response="테스트 인증 실패 또는 알 수 없는 경로입니다. 원문 값은 기록하지 않았습니다.";}
                } catch(Throwable failure){status=409;response="검증 미완료: "+failure.getClass().getSimpleName()+". 민감한 응답 원문은 출력하지 않습니다.";}
                exchange.getResponseHeaders().set("Content-Type","text/html;charset=UTF-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);if(bytes.length>0)exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.start();System.out.println("QA_TOSS_TEST_URL="+base+"/");
            System.out.println("두 사례 완료 후 이 터미널에서 Enter로 종료합니다. 결과에는 결제 키를 저장하지 않습니다.");System.in.read();server.stop(0);
        }
    }
    static Map<String,String> query(String query) {
        Map<String,String> result=new HashMap<>();if(query==null)return result;
        for(String part:query.split("&")){var pair=part.split("=",2);if(pair.length==2)result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}return result;
    }
}

package com.back.coffeeprod.domain.recommendation.ai;

import com.sun.net.httpserver.HttpServer;
import com.back.coffeeprod.global.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class GeminiModelTest {
    HttpServer server;ExecutorService pool;GeminiModel model;
    List<String> paths;List<String> bodies;List<String> keys;
    int count=6000,status=200;long delay=0;boolean stallBody=false;
    String generated;static final JsonMapper JSON=JsonMapper.builder().build();
    @BeforeEach void start()throws Exception {
        paths=new CopyOnWriteArrayList<>();bodies=new CopyOnWriteArrayList<>();keys=new CopyOnWriteArrayList<>();
        generated=response("STOP",1000,500,1548);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);pool=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(pool);
        server.createContext("/models/",exchange->{
            paths.add(exchange.getRequestURI().toString());bodies.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));keys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            try {
                boolean counting=exchange.getRequestURI().getPath().endsWith(":countTokens");
                if(delay>0)Thread.sleep(delay);
                String text=counting?"{\"totalTokens\":"+count+"}":generated;
                byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,stallBody?0:bytes.length);
                if(stallBody){exchange.getResponseBody().write('{');exchange.getResponseBody().flush();Thread.sleep(1000);}
                else exchange.getResponseBody().write(bytes);
            }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(java.io.IOException ignored){}finally{exchange.close();}
        });server.start();model=create("gemini-4-hydrogen");
    }
    GeminiModel create(String name){return new GeminiModel(new AiSettings("gemini",name,"synthetic",new BigDecimal("8")),HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/models/"));}
    String response(String finish,int input,int output,int thoughts){return JSON.writeValueAsString(Map.of("candidates",List.of(Map.of("finishReason",finish,"content",Map.of("parts",List.of(Map.of("text","{\"items\":[]}"))))),"usageMetadata",Map.of("promptTokenCount",input,"candidatesTokenCount",output,"thoughtsTokenCount",thoughts,"totalTokenCount",input+output+thoughts)));}
    @AfterEach void stop(){server.stop(0);pool.shutdownNow();}
    @Test void exactInputAndOutputCapsIncludeSystemSchemaAndThinking() {
        var result=model.generate("{\"preference\":\"고소한 커피\",\"candidates\":[]}",Duration.ofSeconds(15));
        assertTrue(result.complete());assertEquals(new AiModel.Usage(1000,2048),result.usage());assertEquals(2,paths.size());
        var countBody=JSON.readTree(bodies.get(0)).path("generateContentRequest");var generation=JSON.readTree(bodies.get(1));
        assertEquals(generation.path("contents"),countBody.path("contents"));assertEquals(generation.path("systemInstruction"),countBody.path("systemInstruction"));assertEquals(generation.path("generationConfig"),countBody.path("generationConfig"));
        assertEquals("LOW",generation.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText());assertEquals(2048,generation.path("generationConfig").path("maxOutputTokens").asInt());
        assertFalse(generation.has("tools"));assertFalse(generation.has("cachedContent"));assertEquals(List.of("synthetic","synthetic"),keys);assertTrue(paths.stream().noneMatch(p->p.contains("synthetic")));
    }
    @Test void excessInputNeverCallsGenerateAndHasNoRetry() {
        count=6001;assertEquals(ErrorCode.AI_INPUT_TOO_LARGE,assertThrows(CustomException.class,()->model.generate("input",Duration.ofSeconds(15))).getErrorCode());assertEquals(1,paths.size());
    }
    @Test void missingCountFailsClosed() {
        count=-1;assertEquals(ErrorCode.AI_UNAVAILABLE,assertThrows(CustomException.class,()->model.generate("input",Duration.ofSeconds(15))).getErrorCode());assertEquals(1,paths.size());
    }
    @ParameterizedTest @ValueSource(strings={"MAX_TOKENS","SAFETY","OTHER"})
    void incompleteGenerationKeepsUsageButIsNotSuccess(String finish) {
        generated=response(finish,6000,1,2047);var result=model.generate("input",Duration.ofSeconds(15));assertFalse(result.complete());assertEquals(new AiModel.Usage(6000,2048),result.usage());
    }
    @ParameterizedTest @ValueSource(strings={"missing","negative","oversize","total"})
    void invalidUsageIsUnknownAndCannotReduceReservation(String kind) {
        generated=switch(kind){case "missing"->"{}";case "negative"->response("STOP",1000,-5,10);case "oversize"->response("STOP",6001,1,2047);default->response("STOP",1000,500,1548).replace("3048","1");};
        assertNull(model.generate("input",Duration.ofSeconds(15)).usage());
    }
    @Test void providerErrorAndMalformedBodyNeverRetry() {
        status=500;assertEquals(ErrorCode.AI_UNAVAILABLE,assertThrows(CustomException.class,()->model.generate("input",Duration.ofSeconds(15))).getErrorCode());assertEquals(1,paths.size());
        paths.clear();status=200;generated="not JSON";assertThrows(CustomException.class,()->model.generate("input",Duration.ofSeconds(15)));assertEquals(2,paths.size());
    }
    @Test void fullRequestDeadlineIncludesSlowBody() {
        stallBody=true;long start=System.nanoTime();assertEquals(ErrorCode.AI_TIMEOUT,assertThrows(CustomException.class,()->model.generate("input",Duration.ofMillis(150))).getErrorCode());assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<900);assertEquals(1,paths.size());
    }
    @Test void responseSizeLimitStopsMalformedProvider() {
        generated="x".repeat(131073);assertThrows(CustomException.class,()->model.generate("input",Duration.ofSeconds(15)));assertEquals(2,paths.size());
    }
    @Test void compatibleModelSettingUsesSameContractWithoutFallback() {
        var alternate=create("gemini-3.8-flash");assertTrue(alternate.available());assertTrue(alternate.generate("input",Duration.ofSeconds(15)).complete());assertTrue(paths.stream().allMatch(p->p.contains("gemini-3.8-flash")));
    }
    @Test void unsafeSettingsDisabledAndSecretsNotPrintable() {
        for(String name:List.of("gemini-4-carbon","../../evil","https://evil.test"))assertFalse(create(name).available());
        assertFalse(new AiSettings("unknown","gemini-4-hydrogen","key",new BigDecimal("8")).valid());assertFalse(new AiSettings("gemini","gemini-4-hydrogen","",new BigDecimal("8")).valid());
        assertFalse(new AiSettings("gemini","gemini-4-hydrogen","secret",new BigDecimal("9")).valid());assertFalse(new AiSettings("gemini","gemini-4-hydrogen","secret",new BigDecimal("0.0000000001")).valid());assertFalse(new AiSettings("gemini","gemini-4-hydrogen","secret",new BigDecimal("8")).toString().contains("secret"));
    }
    @Test void announcedFlashPriceChangeHasNoUnpricedModelSwitch() {
        var settings=new AiSettings("gemini","gemini-3.8-flash","synthetic",new BigDecimal("8"));
        assertEquals(750,settings.inputRate(LocalDate.of(2026,12,31)));assertEquals(3750,settings.outputRate(LocalDate.of(2026,12,31)));
        assertEquals(1500,settings.inputRate(LocalDate.of(2027,1,1)));assertEquals(7500,settings.outputRate(LocalDate.of(2027,1,1)));
    }

}

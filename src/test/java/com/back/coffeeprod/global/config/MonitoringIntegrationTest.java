package com.back.coffeeprod.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:monitoringtest;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=test",
        "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key",
        "pg.toss.secret-key=test-secret-key",
        "server.address=127.0.0.1",
        "management.server.address=127.0.0.1",
        "management.server.port=0",
        "management.health.redis.enabled=false"
})
class MonitoringIntegrationTest {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("별도 관리 포트에서 인증 없이 상태를 조회하고 상세 정보는 숨긴다")
    void healthIsAccessibleOnManagementPortWithoutDetails() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health");

        assertNotEquals(serverPort, managementPort);
        assertEquals(200, response.statusCode());
        var body = objectMapper.readTree(response.body());
        assertEquals("UP", body.path("status").asString());
        assertFalse(body.has("components"));
        assertFalse(body.has("details"));
    }

    @Test
    @DisplayName("관리 포트에서 인증 없이 JVM CPU DB 풀의 Prometheus 지표를 조회한다")
    void prometheusExportsApplicationMetricsWithoutAuthentication() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("jvm_memory_used_bytes{"));
        assertTrue(response.body().contains("process_cpu_usage "));
        assertTrue(response.body().contains("hikaricp_connections_pending{"));
    }

    @Test
    @DisplayName("공개 API 요청 후 서버 응답 시간 histogram을 수집한다")
    void publicRequestProducesHttpDurationHistogram() throws Exception {
        assertEquals(200, get(serverPort, "/api/v1/categories").statusCode());

        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().lines().anyMatch(line ->
                line.startsWith("http_server_requests_seconds_bucket{")
                        && line.contains("uri=\"/api/v1/categories\"")));
    }

    @Test
    @DisplayName("일반 API 포트에는 Prometheus 지표를 노출하지 않는다")
    void prometheusIsNotExposedOnApplicationPort() throws Exception {
        HttpResponse<String> response = get(serverPort, "/actuator/prometheus");

        assertTrue(response.statusCode() >= 400);
        assertFalse(response.body().contains("jvm_memory_used_bytes"));
    }

    @Test
    @DisplayName("회원 및 관리자 API의 비로그인 접근을 계속 거부한다")
    void protectedApisStillRequireAuthentication() throws Exception {
        assertEquals(401, get(serverPort, "/api/v1/members/me").statusCode());
        assertEquals(401, get(serverPort, "/api/v1/admin/members").statusCode());
    }

    @Test
    @DisplayName("관리 경로는 허용된 GET 요청 외에 인증 예외를 확대하지 않는다")
    void otherManagementRequestsAreNotPublic() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri(managementPort, "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        assertEquals(401, httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode());
        assertEquals(401, get(managementPort, "/actuator/env").statusCode());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri(port, path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}

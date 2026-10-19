package com.back.coffeeprod.qa.recommendation;

import com.back.coffeeprod.domain.product.entity.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class Qa8JsonInputRegressionTest extends Qa8Support {
    static Stream<Arguments> unreadableBodies() {
        return Stream.of("preference", "recommend")
                .flatMap(route -> Stream.of("malformed", "objectInsteadOfNumber")
                        .map(kind -> Arguments.of(route, kind)));
    }

    @ParameterizedTest(name = "읽을 수 없는 JSON / {0} / {1}")
    @MethodSource("unreadableBodies")
    @DisplayName("JSON 문법 오류와 숫자 필드 객체 입력은400이며 저장값을 보존한다")
    void unreadableJsonReturnsBadRequest(String route, String kind) throws Exception {
        data(http("PUT", PREFERENCE, Map.of("decaf", false), token(owner)), 200);
        product(profile(single()), 15000, 3, ProductStatus.ON_SALE);
        var before = state();
        String body = kind.equals("malformed")
                ? "{\"roastLevel\":"
                : "{\"preferredAcidity\":{\"value\":3}}";
        boolean preference = route.equals("preference");
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + (preference ? PREFERENCE : RECOMMEND)))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (preference) {
            request.header("Authorization", "Bearer " + token(owner));
        }
        var response = HTTP.send(request.method(preference ? "PUT" : "POST",
                HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());

        unchanged(before);
        var result = root(response, 400);
        assertEquals("잘못된 입력 값입니다.", result.path("message").asText());
        assertTrue(result.path("data").isNull());
        assertTrue(result.path("errors").isNull());
        assertEquals(4, result.size(), "기존 CommonResponse 필드만 반환");
    }
}

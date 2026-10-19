package com.back.coffeeprod.global.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {
    @Test
    @DisplayName("일반 서버 예외는500이며 CommonResponse에 내부 예외 정보를 노출하지 않는다")
    void unexpectedServerFailureRemainsInternalServerError() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        var response = mvc.perform(get("/dev6-test/server-error"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse();
        assertTrue(response.getContentType().contains("application/json"));
        var result = JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertEquals(500, result.path("status").asInt());
        assertEquals("서버 내부 오류입니다.", result.path("message").asText());
        assertTrue(result.path("data").isNull());
        assertTrue(result.path("errors").isNull());
        assertEquals(4, result.size());
    }

    @RestController
    static class FailingController {
        @GetMapping("/dev6-test/server-error")
        String fail() {
            throw new IllegalStateException("dev6-synthetic-private-detail");
        }
    }
}

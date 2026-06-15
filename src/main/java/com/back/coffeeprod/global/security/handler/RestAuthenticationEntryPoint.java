package com.back.coffeeprod.global.security.handler;

import com.back.coffeeprod.global.common.CommonResponse;
import com.back.coffeeprod.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    // 인증되지 않은 사용자가 인증 필요 API에 접근할 때 401 응답을 반환
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {

        ErrorCode errorCode = ErrorCode.AUTHENTICATION_REQUIRED;

        response.setStatus(errorCode.getStatus());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        objectMapper.writeValue(
                response.getWriter(),
                CommonResponse.error(errorCode.getStatus(), errorCode.getMessage())
        );
    }
}

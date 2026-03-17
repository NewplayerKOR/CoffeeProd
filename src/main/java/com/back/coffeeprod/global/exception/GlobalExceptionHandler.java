package com.back.coffeeprod.global.exception;

import com.back.coffeeprod.global.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 비즈니스 로직에서 발생하는 CustomException 처리
    @ExceptionHandler(CustomException.class)
    public ApiResponse<Void> handleCustomException(CustomException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("CustomException occurs: {}", errorCode.getMessage());

        // ApiReponse의 error() 메서드를 사용하여 에러 응답 생성
        return ApiResponse.error(errorCode.getStatus(), errorCode.getMessage());
    }

    // 그 외 예상치 못한 예외 처리
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("Internal Server Error: ", e);

        return ApiResponse.error(
                ErrorCode.INTERNAL_SERVER_ERROR.getStatus(),
                ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
    }
}

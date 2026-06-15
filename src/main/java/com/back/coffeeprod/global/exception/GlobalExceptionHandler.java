package com.back.coffeeprod.global.exception;

import com.back.coffeeprod.global.common.CommonResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. 비즈니스 로직에서 발생하는 CustomException 처리
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<CommonResponse<Void>> handleCustomException(CustomException e) {
        ErrorCode errorCode = e.getErrorCode();

        log.warn("CustomException occurs: {} (Status: {})", errorCode.getMessage(), errorCode.getStatus());

        return ResponseEntity
                .status(errorCode.getStatus()) // HTTP 응답 헤더에 상태 코드 주입
                .body(CommonResponse.error(errorCode.getStatus(), errorCode.getMessage())); // 바디에 에러 정보 주입
    }

    // 2. 그 외 예상치 못한 모든 예외(500 에러) 처리
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResponse<Void>> handleException(Exception e) {
        log.error("Internal Server Error: ", e);

        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(CommonResponse.error(errorCode.getStatus(), errorCode.getMessage()));
    }

    // 요청 DTO 검증 실패 처리
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResponse<Void>> handleMethodArgumentNotValidException (
            MethodArgumentNotValidException e) {

        List<CommonResponse.ValidationError> errors = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new CommonResponse.ValidationError(
                        error.getField(),
                        error.getDefaultMessage()
                ))
                .toList();

        return ResponseEntity
                .badRequest()
                .body(CommonResponse.validationError(
                        ErrorCode.INVALID_INPUT_VALUE.getStatus(),
                        ErrorCode.INVALID_INPUT_VALUE.getMessage(),
                        errors
                ));
    }
}
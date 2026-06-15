package com.back.coffeeprod.global.common;

import lombok.Getter;

import java.util.List;

@Getter
public class CommonResponse<T> {
    private final int status;
    private final String message;
    private final T data;
    private final List<ValidationError> errors;

    // 외부에서 무분별한 생성 방지
    private CommonResponse(int status, String message, T data, List<ValidationError> errors) {
        this.status = status;
        this.message = message;
        this.data = data;
        this.errors = errors;
    }

    // 1. 성공 응답 (데이터 있음)
    public static <T> CommonResponse<T> success(T data) {
        return new CommonResponse<>(200, "성공", data, null);
    }

    // 2. 성공 응답 (데이터 없음)
    public static <T> CommonResponse<T> success(int status, String message) {
        return new CommonResponse<>(status, message, null, null);
    }

    // 3. 에러/예외 응답
    public static <T> CommonResponse<T> error(int status, String message) {
        return new CommonResponse<>(status, message, null, null);
    }

    // 4. 요청 검증 실패 응답
    public static <T> CommonResponse<T> validationError(int status, String message, List<ValidationError> errors) {
        return new CommonResponse<>(status, message, null, errors);
    }

    @Getter
    public static class ValidationError {
        private final String field;
        private final String message;

        public ValidationError(String field, String message) {
            this.field = field;
            this.message = message;
        }
    }
}
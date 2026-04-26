package com.back.coffeeprod.global.common;

import lombok.Getter;

@Getter
public class CommonResponse<T> {
    private final int status;
    private final String message;
    private final T data;

    // 외부에서 무분별한 생성 방지
    private CommonResponse(int status, String message, T data) {
        this.status = status;
        this.message = message;
        this.data = data;
    }

    // 1. 성공 응답 (데이터 있음)
    public static <T> CommonResponse<T> success(T data) {
        return new CommonResponse<>(200, "성공", data);
    }

    // 2. 성공 응답 (데이터 없음)
    public static <T> CommonResponse<T> success(int status, String message) {
        return new CommonResponse<>(status, message, null);
    }

    // 3. 에러/예외 응답
    public static <T> CommonResponse<T> error(int status, String message) {
        return new CommonResponse<>(status, message, null);
    }
}
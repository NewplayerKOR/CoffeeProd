package com.back.coffeeprod.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // [회원 관련]
    MEMBER_NOT_FOUND(404, "회원 정보를 찾을 수 없습니다."),
    INVALID_CREDENTIALS(401, "이메일 또는 비밀번호가 일치하지 않습니다."),
    INVALID_PASSWORD(401, "비밀번호가 일치하지 않습니다."),
    DUPLICATE_EMAIL(409, "이미 사용 중인 이메일입니다."),
    DUPLICATE_NICKNAME(409, "이미 사용 중인 닉네임입니다."),

    // [상품/주문]
    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    OUT_OF_STOCK(400, "재고가 부족합니다."),
    INVALID_ORDER_STATUS(400, "변경할 수 없는 주문 상태입니다."),

    // [공통 에러]
    INVALID_INPUT_VALUE(400, "잘못된 입력 값입니다."),
    INTERNAL_SERVER_ERROR(500, "서버 내부 오류입니다.");

    private final int status;
    private final String message;
}

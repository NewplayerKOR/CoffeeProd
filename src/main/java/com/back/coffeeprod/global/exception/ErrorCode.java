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
    WITHDRAW_MEMBER(401, "탈퇴한 회원입니다."),
    SUSPENDED_MEMBER(403, "정지된 회원입니다."),

    // [인증 관련]
    INVALID_TOKEN(401, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(401, "만료된 토큰입니다."),

    // [상품/주문]
    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    CATEGORY_NOT_FOUND(404, "카테고리를 찾을 수 없습니다."),
    DUPLICATE_CATEGORY_NAME(409, "이미 존재하는 카테고리명입니다."),
    OUT_OF_STOCK(400, "재고가 부족합니다."),
    INVALID_ORDER_STATUS(400, "변경할 수 없는 주문 상태입니다."),

    // [주문/결제 관련]
    ORDER_NOT_FOUND(404, "주문을 찾을 수 없습니다."),
    ORDER_ACCESS_DENIED(403, "본인의 주문만 접근할 수 있습니다."),
    INVALID_ORDER_AMOUNT(400, "결제 금액이 주문과 일치하지 않습니다."),
    EMPTY_CART(400, "장바구니가 비어있습니다."),
    PAYMENT_FAILED(400, "결제 승인에 실패했습니다."),
    PAYMENT_NOT_FOUND(404, "결제 정보를 찾을 수 없습니다."),
    INVALID_MILEAGE(400, "사용 가능한 마일리지를 초과했습니다."),

    // [장바구니 관련]
    CART_NOT_FOUND(404, "장바구니를 찾을 수 없습니다."),
    CART_ITEM_NOT_FOUND(404, "장바구니 상품을 찾을 수 없습니다."),
    CART_ACCESS_DENIED(403, "본인의 장바구니만 접근할 수 있습니다."),
    PRODUCT_NOT_ON_SALE(400, "현재 판매 중인 상품이 아닙니다."),

    // [배송지 관련]
    ADDRESS_NOT_FOUND(404, "배송지를 찾을 수 없습니다."),
    ADDRESS_ACCESS_DENIED(403, "본인의 배송지만 접근할 수 있습니다."),
    ADDRESS_LIMIT_EXCEEDED(400, "배송지는 최대 5개까지 등록할 수 있습니다."),


    // [공통 에러]
    INVALID_INPUT_VALUE(400, "잘못된 입력 값입니다."),
    INTERNAL_SERVER_ERROR(500, "서버 내부 오류입니다.");

    private final int status;
    private final String message;
}

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
    AUTHENTICATION_REQUIRED(401, "인증이 필요합니다."),
    ACCESS_DENIED(403, "접근 권한이 없습니다."),
    INVALID_TOKEN(401, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(401, "만료된 토큰입니다."),

    // [상품/주문]
    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    CATEGORY_NOT_FOUND(404, "카테고리를 찾을 수 없습니다."),
    DUPLICATE_CATEGORY_NAME(409, "이미 존재하는 카테고리명입니다."),
    DUPLICATE_PRODUCT_SKU(409, "이미 존재하는 상품 SKU입니다."),
    CATEGORY_IN_USE(400, "상품이 등록된 카테고리는 삭제할 수 없습니다."),
    OUT_OF_STOCK(400, "재고가 부족합니다."),
    INVALID_ORDER_STATUS(400, "변경할 수 없는 주문 상태입니다."),

    // [커피 추천 관련]
    RECOMMENDATION_PREFERENCE_REQUIRED(400, "추천 조건을 하나 이상 입력해야 합니다."),
    COFFEE_PREFERENCE_NOT_FOUND(404, "저장된 커피 취향을 찾을 수 없습니다."),
    COFFEE_PREFERENCE_REQUIRED(400, "커피 취향을 하나 이상 입력해야 합니다."),
    INVALID_RECOMMENDATION_LIMIT(400, "추천 개수는 1개 이상 10개 이하여야 합니다."),

    // [주문/결제 관련]
    COFFEE_PROFILE_NOT_FOUND(404, "커피 프로필을 찾을 수 없습니다."),
    PROCESSING_METHOD_NOT_FOUND(404, "가공방식을 찾을 수 없습니다."),
    DUPLICATE_PROCESSING_METHOD_CODE(409, "이미 존재하는 가공 방식 코드입니다."),
    INVALID_COFFEE_PROFILE(400, "커피 프로필 입력값이 올바르지 않습니다."),

    // [커피 카탈로그 관련]
    FLAVOR_NOTE_NOT_FOUND(404, "향미 노트를 찾을 수 없습니다."),
    BREW_METHOD_NOT_FOUND(404, "추천 추출법을 찾을 수 없습니다."),
    DUPLICATE_FLAVOR_NOTE_CODE(409, "이미 존재하는 향미 노트 코드입니다."),
    DUPLICATE_BREW_METHOD_CODE(409, "이미 존재하는 추천 추출법 코드입니다."),
    DUPLICATE_COFFEE_PROFILE_FLAVOR_NOTE(400, "동일한 향미 노트를 중복 연결할 수 없습니다."),
    DUPLICATE_COFFEE_PROFILE_BREW_METHOD(400, "동일한 추천 추출법을 중복 연결할 수 없습니다."),
    COFFEE_VARIETY_NOT_FOUND(404, "커피 품종을 찾을 수 없습니다."),
    DUPLICATE_COFFEE_VARIETY_CODE(409, "이미 존재하는 커피 품종 코드입니다."),
    DUPLICATE_COFFEE_PROFILE_VARIETY(400, "동일한 커피 품종을 중복 연결할 수 없습니다."),

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

    // [리뷰 관련]
    REVIEW_NOT_FOUND(404, "리뷰를 찾을 수 없습니다."),
    REVIEW_ACCESS_DENIED(403, "본인의 리뷰만 접근할 수 있습니다."),
    REVIEW_ALREADY_EXISTS(409, "이미 리뷰를 작성한 상품입니다."),
    REVIEW_PURCHASE_REQUIRED(403, "구매한 상품만 리뷰를 작성할 수 있습니다."),

    // [QnA 관련]
    QNA_NOT_FOUND(404, "QnA를 찾을 수 없습니다."),
    QNA_ACCESS_DENIED(403, "본인의 QnA만 접근할 수 있습니다."),
    QNA_ALREADY_ANSWERED(409, "답변이 완료된 QnA입니다."),

    // [매출 통계 관련]
    INVALID_STATISTICS_DATE_RANGE(400, "매출 통계 시작일은 종료일보다 늦을 수 없습니다."),
    STATISTICS_DATE_RANGE_TOO_LARGE(400, "매출 통계 재집계 기간은 최대 366일까지 가능합니다."),

    // [공통 에러]
    INVALID_INPUT_VALUE(400, "잘못된 입력 값입니다."),
    INTERNAL_SERVER_ERROR(500, "서버 내부 오류입니다.");

    private final int status;
    private final String message;
}

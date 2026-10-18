package com.stove.order.core.domain;

import com.stove.common.web.error.ApiErrorCode;

public enum OrderErrorCode implements ApiErrorCode {
    PURCHASE_IN_PROGRESS(409, "같은 상품의 결제가 진행 중입니다."),
    PRODUCT_ALREADY_PURCHASED(409, "이미 구매한 상품입니다."),
    CATALOG_INVALID_RESPONSE(502, "Catalog 공개 상품 응답이 올바르지 않습니다.");

    private final int status;
    private final String message;
    OrderErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String code() { return name(); }
    public int statusCode() { return status; }
    public String defaultMessage() { return message; }
}

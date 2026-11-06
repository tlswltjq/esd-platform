package com.stove.order.core.domain;

import com.stove.common.web.error.ApiErrorCode;

public enum OrderErrorCode implements ApiErrorCode {
    PRODUCT_NOT_ON_SALE(409, "판매 중지된 상품은 새로 주문할 수 없습니다."),
    PRODUCT_SALE_RESTRICTED(409, "관리자 판매 제한으로 새 승인 진행을 시작할 수 없습니다."),
    ORDER_EXPIRED(409, "주문의 결제 유효기간이 지났습니다."),
    CATALOG_INVALID_RESPONSE(502, "Catalog 공개 상품 응답이 올바르지 않습니다.");

    private final int status;
    private final String message;
    OrderErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String code() { return name(); }
    public int statusCode() { return status; }
    public String defaultMessage() { return message; }
}

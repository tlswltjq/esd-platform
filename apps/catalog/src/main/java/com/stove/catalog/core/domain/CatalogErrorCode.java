package com.stove.catalog.core.domain;

import com.stove.common.web.error.ApiErrorCode;

/** catalog 업무 오류. 코드와 HTTP 상태는 기존 API 계약을 유지한다. */
public enum CatalogErrorCode implements ApiErrorCode {

    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    PRODUCT_NOT_ON_SALE(409, "판매 중인 상품이 아닙니다.");

    private final int statusCode;
    private final String defaultMessage;

    CatalogErrorCode(int statusCode, String defaultMessage) {
        this.statusCode = statusCode;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() { return name(); }

    @Override
    public int statusCode() { return statusCode; }

    @Override
    public String defaultMessage() { return defaultMessage; }
}

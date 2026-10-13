package com.stove.order.core.domain;

import com.stove.common.web.error.ApiErrorCode;

/** order 업무 오류. 코드와 HTTP 상태는 기존 API 계약을 유지한다. */
public enum OrderErrorCode implements ApiErrorCode {

    ORDER_NOT_FOUND(404, "주문을 찾을 수 없습니다."),
    ORDER_NOT_CANCELABLE(409, "취소할 수 없는 주문 상태입니다."),
    PRICE_MISMATCH(409, "상품 가격이 변경되었습니다. 다시 시도해 주세요.");

    private final int statusCode;
    private final String defaultMessage;

    OrderErrorCode(int statusCode, String defaultMessage) {
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

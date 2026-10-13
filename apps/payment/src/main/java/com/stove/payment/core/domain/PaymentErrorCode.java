package com.stove.payment.core.domain;

import com.stove.common.web.error.ApiErrorCode;

/** payment 업무 오류. 코드와 HTTP 상태는 기존 API 계약을 유지한다. */
public enum PaymentErrorCode implements ApiErrorCode {

    PAYMENT_NOT_FOUND(404, "결제 정보를 찾을 수 없습니다."),
    PAYMENT_AMOUNT_MISMATCH(409, "결제 요청 금액과 승인 금액이 일치하지 않습니다."),
    PAYMENT_TX_MISMATCH(409, "PG 거래번호가 일치하지 않습니다."),
    PAYMENT_ALREADY_PROCESSED(409, "이미 처리된 결제입니다."),
    PAYMENT_WINDOW_EXPIRED(409, "주문의 결제 가능 시간이 지났습니다. 다시 주문해 주세요.");

    private final int statusCode;
    private final String defaultMessage;

    PaymentErrorCode(int statusCode, String defaultMessage) {
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

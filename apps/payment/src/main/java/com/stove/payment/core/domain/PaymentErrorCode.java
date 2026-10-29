package com.stove.payment.core.domain;

import com.stove.common.web.error.ApiErrorCode;

public enum PaymentErrorCode implements ApiErrorCode {
    ORDER_EXPIRED(409,"The checkout deadline has passed"),
    APPROVAL_IN_PROGRESS(409,"The existing approval is still processing");
    private final int status;
    private final String message;
    PaymentErrorCode(int status,String message) { this.status=status; this.message=message; }
    public String code() { return name(); }
    public int statusCode() { return status; }
    public String defaultMessage() { return message; }
}

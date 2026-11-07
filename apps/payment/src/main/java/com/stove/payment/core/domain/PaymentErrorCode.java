package com.stove.payment.core.domain;

import com.stove.common.web.error.ApiErrorCode;

public enum PaymentErrorCode implements ApiErrorCode {
    ORDER_EXPIRED(409,"The checkout deadline has passed"),
    APPROVAL_IN_PROGRESS(409,"The existing approval is still processing"),
    // Bounded refusals of an operator closure of a held approval (decision 115). None of them moves money.
    APPROVAL_NOT_HELD(409,"The approval is not currently held for review"),
    APPROVAL_CLOSURE_BUSY(409,"The approval is being processed; retry the closure later"),
    APPROVAL_CLOSURE_CHANGED(409,"The closure request ID was already used with different content"),
    CLOSURE_PG_PAID(409,"The PG reports the payment as paid; cancel it in the PG console before closing"),
    CLOSURE_PG_PENDING(409,"The PG payment is still pending; retry the closure later"),
    CLOSURE_PG_INVALID_EVIDENCE(409,"The PG evidence does not match the held purchase"),
    CLOSURE_PG_UNAVAILABLE(409,"The PG lookup is unavailable; retry the closure later"),
    CLOSURE_PAYMENT_RESOLVED(409,"The purchase already has a final result");
    private final int status;
    private final String message;
    PaymentErrorCode(int status,String message) { this.status=status; this.message=message; }
    public String code() { return name(); }
    public int statusCode() { return status; }
    public String defaultMessage() { return message; }
}

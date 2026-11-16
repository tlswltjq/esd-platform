package com.stove.payment.core.domain;

import com.stove.common.web.error.ApiErrorCode;

public enum PaymentErrorCode implements ApiErrorCode {
    ORDER_EXPIRED(409,"The checkout deadline has passed"),
    APPROVAL_IN_PROGRESS(409,"기존 결제 승인을 처리하고 있습니다."),
    // Bounded refusals of an operator closure of a held approval (decision 115). None of them moves money.
    APPROVAL_NOT_HELD(409,"The approval is not currently held for review"),
    APPROVAL_CLOSURE_BUSY(409,"The approval is being processed; retry the closure later"),
    APPROVAL_CLOSURE_CHANGED(409,"The closure request ID was already used with different content"),
    CLOSURE_PG_PAID(409,"The PG reports the payment as paid; cancel it in the PG console before closing"),
    CLOSURE_PG_PENDING(409,"The PG payment is still pending; retry the closure later"),
    CLOSURE_PG_INVALID_EVIDENCE(409,"The PG evidence does not match the held purchase"),
    CLOSURE_PG_UNAVAILABLE(409,"The PG lookup is unavailable; retry the closure later"),
    CLOSURE_PAYMENT_RESOLVED(409,"The purchase already has a final result"),
    // Bounded refusals of an operator closure of a delayed or unattributed refund (decision 127). None of them moves money.
    REFUND_NOT_CLOSABLE(409,"The refund attempt is not delayed past its window or unattributed, or it is already closed"),
    REFUND_CLOSURE_BUSY(409,"The refund lookup or a failure closure is in progress; retry later"),
    REFUND_CLOSURE_CHANGED(409,"The closure request ID was already used with different content"),
    REFUND_CLOSURE_PG_NOT_CANCELLED(409,"The PG does not report a full cancellation of the original payment; cancel it in the PG console before closing"),
    // Bounded refusals of an operator failure closure of a stopped refund (decision 128). None of them moves money.
    REFUND_NOT_FAILURE_CLOSABLE(409,"The refund attempt's automatic cancellation has not stopped, a cancellation request is in flight, or it already has a result"),
    REFUND_FAILURE_CLOSURE_PG_NOT_UNCANCELLED(409,"The PG reports a cancelled amount or a requested or successful cancellation; the refund cannot be closed as failed");
    private final int status;
    private final String message;
    PaymentErrorCode(int status,String message) { this.status=status; this.message=message; }
    public String code() { return name(); }
    public int statusCode() { return status; }
    public String defaultMessage() { return message; }
}

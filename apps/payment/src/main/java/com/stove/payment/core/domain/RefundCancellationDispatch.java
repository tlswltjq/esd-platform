package com.stove.payment.core.domain;

/** Only a validated response carries successful execution evidence. */
public record RefundCancellationDispatch(Observation observation, PgRefundCancellationSuccess success, PgRefundCancellationReceipt receipt,
        PgCancellationRejection rejection) {
    public static final RefundCancellationDispatch UNCONFIRMED = new RefundCancellationDispatch(Observation.UNCONFIRMED, null, null, null);
    public static final RefundCancellationDispatch BALANCE_CHANGED = new RefundCancellationDispatch(Observation.BALANCE_CHANGED, null, null, null);
    public static final RefundCancellationDispatch NOT_READY = new RefundCancellationDispatch(Observation.NOT_READY, null, null, null);
    public static final RefundCancellationDispatch FORBIDDEN = new RefundCancellationDispatch(Observation.FORBIDDEN, null, null, null);
    public static final RefundCancellationDispatch INVALID_REQUEST = new RefundCancellationDispatch(Observation.INVALID_REQUEST, null, null, null);

    public RefundCancellationDispatch {
        if (observation == null || (observation == Observation.SUCCEEDED) != (success != null)
                || (observation == Observation.RECEIVED) != (receipt != null)
                || (observation == Observation.REJECTED) != (rejection != null))
            throw new IllegalArgumentException("Successful dispatch requires validated evidence");
    }
    public static RefundCancellationDispatch succeeded(PgRefundCancellationSuccess success) {
        return new RefundCancellationDispatch(Observation.SUCCEEDED, success, null, null);
    }
    public static RefundCancellationDispatch received(PgRefundCancellationReceipt receipt) {
        return new RefundCancellationDispatch(Observation.RECEIVED, null, receipt, null);
    }
    /** A well-formed typed 4xx that stops automatic POSTs (decision 133); it is not a failed refund. */
    public static RefundCancellationDispatch rejected(PgCancellationRejection rejection) {
        return new RefundCancellationDispatch(Observation.REJECTED, null, null, rejection);
    }
    public enum Observation { UNCONFIRMED, BALANCE_CHANGED, NOT_READY, SUCCEEDED, RECEIVED, FORBIDDEN, INVALID_REQUEST, REJECTED }
}

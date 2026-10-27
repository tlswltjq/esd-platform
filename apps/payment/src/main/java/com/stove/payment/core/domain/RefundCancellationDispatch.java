package com.stove.payment.core.domain;

/** Only a validated response carries successful execution evidence. */
public record RefundCancellationDispatch(Observation observation, PgRefundCancellationSuccess success, PgRefundCancellationReceipt receipt) {
    public static final RefundCancellationDispatch UNCONFIRMED = new RefundCancellationDispatch(Observation.UNCONFIRMED, null, null);
    public static final RefundCancellationDispatch BALANCE_CHANGED = new RefundCancellationDispatch(Observation.BALANCE_CHANGED, null, null);
    public static final RefundCancellationDispatch NOT_READY = new RefundCancellationDispatch(Observation.NOT_READY, null, null);

    public RefundCancellationDispatch {
        if (observation == null || (observation == Observation.SUCCEEDED) != (success != null)
                || (observation == Observation.RECEIVED) != (receipt != null))
            throw new IllegalArgumentException("Successful dispatch requires validated evidence");
    }
    public static RefundCancellationDispatch succeeded(PgRefundCancellationSuccess success) {
        return new RefundCancellationDispatch(Observation.SUCCEEDED, success, null);
    }
    public static RefundCancellationDispatch received(PgRefundCancellationReceipt receipt) {
        return new RefundCancellationDispatch(Observation.RECEIVED, null, receipt);
    }
    public enum Observation { UNCONFIRMED, BALANCE_CHANGED, NOT_READY, SUCCEEDED, RECEIVED }
}

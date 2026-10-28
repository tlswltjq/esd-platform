package com.stove.payment.core.domain;

/** A missing cancellation target is unavailable evidence, never a confirmed refund failure. */
public final class PgCancellationNotFound extends PgUnavailable {
    public PgCancellationNotFound() { super("PG cancellation could not locate the purchase; refund outcome remains unconfirmed"); }
}

package com.stove.payment.core.domain;

/** A missing supplier lookup is unavailable evidence, never a reversal of our paid purchase. */
public final class PgPaymentNotFound extends PgUnavailable {
    public PgPaymentNotFound() { super("PG payment lookup could not locate the purchase; refund outcome remains unconfirmed"); }
}

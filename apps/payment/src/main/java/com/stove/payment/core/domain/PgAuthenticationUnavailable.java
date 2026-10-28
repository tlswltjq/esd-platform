package com.stove.payment.core.domain;

/** An authentication problem can recover; it is never evidence that a refund failed. */
public final class PgAuthenticationUnavailable extends PgUnavailable {
    public PgAuthenticationUnavailable() { super("PG authentication unavailable; refund outcome remains unconfirmed"); }
}

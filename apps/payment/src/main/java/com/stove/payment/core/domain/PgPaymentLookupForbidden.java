package com.stove.payment.core.domain;

/** A rejected lookup cannot determine the refund outcome or establish a cancellation rejection. */
public final class PgPaymentLookupForbidden extends PgUnavailable {
    public PgPaymentLookupForbidden() { super("PG payment lookup was forbidden; refund outcome remains unconfirmed"); }
}

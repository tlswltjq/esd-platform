package com.stove.payment.core.domain;

/** Unknown supplier outcome, never a final payment decline. */
public class PgUnavailable extends RuntimeException {
    public PgUnavailable(String message) { super(message); }
}

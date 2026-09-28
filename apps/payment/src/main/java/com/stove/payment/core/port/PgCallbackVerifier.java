package com.stove.payment.core.port;

/** Verifies the exact bytes received from a PG before any payment state can change. */
public interface PgCallbackVerifier {
    boolean verify(byte[] body, String timestamp, String signature);
}

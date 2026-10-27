package com.stove.payment.core.port;

/** Verifies the exact bytes received from the provider before parsing them. */
public interface PortOneWebhookVerifier {
    void verify(byte[] body, String messageId, String timestamp, String signatures);
}

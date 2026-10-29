package com.stove.payment.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ApprovalToken {
    private final String value;
    public ApprovalToken(String value) {
        if (!PaymentValues.text(value, 8192)) throw new IllegalArgumentException("Invalid approval token");
        this.value = value;
    }
    public String value() { return value; }
    public String fingerprint() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required"); }
    }
    @Override public String toString() { return "ApprovalToken[redacted]"; }
}

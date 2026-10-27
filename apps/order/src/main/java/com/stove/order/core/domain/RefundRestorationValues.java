package com.stove.order.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

final class RefundRestorationValues {
    private RefundRestorationValues() { }
    static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    static boolean uuid(String value) { return value != null && value.matches(OrderValues.UUID_PATTERN); }
    static boolean time(Instant value) { return value != null && value.getNano() % 1000 == 0; }
    static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    static String fingerprint(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}

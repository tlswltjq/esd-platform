package com.stove.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PortOneFixtures {
    public static final Instant NOW = Instant.parse("2026-10-27T01:02:03Z");
    public static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(
            "portone-test-signature-key-32byte!".getBytes(StandardCharsets.UTF_8));
    public static final String STORE = "store-11111111-1111-1111-1111-111111111111";
    public static final String CHANNEL = "channel-key-22222222-2222-2222-2222-222222222222";

    private PortOneFixtures() { }

    public static String sign(String id, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET.substring(6)), "HmacSHA256"));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }
}

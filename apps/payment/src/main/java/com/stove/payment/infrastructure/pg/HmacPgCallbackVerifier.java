package com.stove.payment.infrastructure.pg;

import com.stove.payment.core.port.PgCallbackVerifier;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Local PG contract: HMAC-SHA256(timestamp + '.' + raw JSON), valid for five minutes. */
@Component
public class HmacPgCallbackVerifier implements PgCallbackVerifier {

    private static final String LOCAL_SECRET = "local-only-pg-callback-secret";
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private final byte[] secret;

    public HmacPgCallbackVerifier(@Value("${stove.payment.callback-secret:}") String secret,
                                  Environment environment) {
        if (secret.isBlank() || (environment.acceptsProfiles(Profiles.of("prod"))
                && LOCAL_SECRET.equals(secret))) {
            throw new IllegalStateException("PG_CALLBACK_SECRET must be configured outside local/test");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean verify(byte[] body, String timestamp, String signature) {
        if (timestamp == null || signature == null || signature.isBlank()) return false;
        try {
            Instant sentAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
            if (Duration.between(sentAt, Instant.now()).abs().compareTo(WINDOW) > 0) return false;
            byte[] submitted = HexFormat.of().parseHex(signature);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            byte[] expected = mac.doFinal(body);
            return MessageDigest.isEqual(expected, submitted);
        } catch (IllegalArgumentException | GeneralSecurityException invalid) {
            return false;
        }
    }
}

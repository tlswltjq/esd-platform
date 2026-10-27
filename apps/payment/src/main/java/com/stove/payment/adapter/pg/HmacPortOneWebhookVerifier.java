package com.stove.payment.adapter.pg;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.port.PortOneWebhookVerifier;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class HmacPortOneWebhookVerifier implements PortOneWebhookVerifier {
    private final byte[] key;
    private final Clock clock;
    private final long toleranceSeconds;

    public HmacPortOneWebhookVerifier(String secret, Clock clock, Duration tolerance) {
        if (secret == null || !secret.startsWith("whsec_") || clock == null || tolerance == null
                || tolerance.isNegative() || tolerance.isZero()) {
            throw new IllegalArgumentException("A webhook signing secret, clock and tolerance are required");
        }
        try { key = Base64.getDecoder().decode(secret.substring(6)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid webhook signing secret"); }
        if (key.length < 24 || key.length > 64) throw new IllegalArgumentException("Invalid webhook signing secret length");
        this.clock = clock;
        toleranceSeconds = tolerance.toSeconds();
    }

    @Override
    public void verify(byte[] body, String messageId, String timestamp, String signatures) {
        if (body == null || body.length == 0 || messageId == null || !messageId.matches("[A-Za-z0-9_-]{1,200}")
                || timestamp == null || !timestamp.matches("[0-9]{1,19}")
                || signatures == null || signatures.length() > 2048) reject();
        long deliveredAt;
        try { deliveredAt = Long.parseLong(timestamp); }
        catch (NumberFormatException invalid) { reject(); return; }
        long now = clock.instant().getEpochSecond();
        if (deliveredAt < now - toleranceSeconds || deliveredAt > now + toleranceSeconds) reject();
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((messageId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            expected = mac.doFinal(body);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", unavailable);
        }
        for (String candidate : signatures.split(" +")) {
            if (!candidate.startsWith("v1,")) continue;
            try {
                if (MessageDigest.isEqual(expected, Base64.getDecoder().decode(candidate.substring(3)))) return;
            } catch (IllegalArgumentException invalid) {
                // Another signature may match during a provider secret rotation.
            }
        }
        reject();
    }

    private static void reject() { throw new BusinessException(ErrorCode.UNAUTHORIZED, "웹훅 서명을 확인할 수 없습니다."); }
}

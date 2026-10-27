package com.stove.payment.adapter.pg;

import static com.stove.payment.PortOneFixtures.NOW;
import static com.stove.payment.PortOneFixtures.SECRET;
import static com.stove.payment.PortOneFixtures.sign;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HmacPortOneWebhookVerifierTest {
    private final byte[] body = "{\"type\":\"Transaction.Paid\", \"한글\":true}".getBytes(StandardCharsets.UTF_8);
    private final String id = "msg_test_1";
    private final String timestamp = Long.toString(NOW.getEpochSecond());
    private final HmacPortOneWebhookVerifier verifier = new HmacPortOneWebhookVerifier(
            SECRET, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

    @Test void exactBodyAndMetadataAreVerifiedWithoutReserializingJson() {
        assertThatCode(() -> verifier.verify(body, id, timestamp, sign(id, timestamp, body))).doesNotThrowAnyException();
    }

    @Test void secretRotationAllowsAnyMatchingSupportedSignature() {
        String signatures = "v2,unknown v1,AAAA " + sign(id, timestamp, body);
        assertThatCode(() -> verifier.verify(body, id, timestamp, signatures)).doesNotThrowAnyException();
    }

    @Test void modifiedBodyIdAndTimestampCannotReuseASignature() {
        String signature = sign(id, timestamp, body);
        assertThatThrownBy(() -> verifier.verify("{}".getBytes(StandardCharsets.UTF_8), id, timestamp, signature))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verifier.verify(body, "msg_other", timestamp, signature)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verifier.verify(body, id, Long.toString(NOW.getEpochSecond() + 1), signature))
                .isInstanceOf(BusinessException.class);
    }

    @Test void oldAndFutureDeliveryTimestampsAreRejectedButTheExactBoundaryIsAllowed() {
        for (long offset : new long[] {-301, 301, Long.MAX_VALUE}) {
            String time = Long.toString(offset == Long.MAX_VALUE ? offset : NOW.getEpochSecond() + offset);
            assertThatThrownBy(() -> verifier.verify(body, id, time, sign(id, time, body))).isInstanceOf(BusinessException.class);
        }
        String boundary = Long.toString(NOW.getEpochSecond() - 300);
        assertThatCode(() -> verifier.verify(body, id, boundary, sign(id, boundary, body))).doesNotThrowAnyException();
    }

    @Test void malformedMetadataAndUnsupportedSignaturesAreRejected() {
        assertThatThrownBy(() -> verifier.verify(body, "id.with.dot", timestamp, sign("id.with.dot", timestamp, body)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verifier.verify(body, id, "bad", "v1,AAAA")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verifier.verify(body, id, timestamp, "v2,AAAA")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verifier.verify(body, id, timestamp, "v1,not-base64!")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verifier.verify(body, id, timestamp, null)).isInstanceOf(BusinessException.class);
    }
}

package com.stove.payment.api.listener;

import static com.stove.payment.PortOneFixtures.NOW;
import static com.stove.payment.PortOneFixtures.STORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.PortOneWebhookNotice;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PortOneWebhookMapperTest {
    private final PortOneWebhookMapper mapper = new PortOneWebhookMapper(new ObjectMapper(), STORE);

    @Test void signedMetadataAndBodyBecomeAnInternalHintWithAnExactBodyFingerprint() {
        byte[] body = body("Transaction.Paid", STORE);
        PortOneWebhookNotice notice = mapper.from(body, "msg_test").orElseThrow();
        assertThat(notice.messageId()).isEqualTo("msg_test");
        assertThat(notice.type()).isEqualTo("Transaction.Paid");
        assertThat(notice.paymentId()).isEqualTo("payment-test");
        assertThat(notice.transactionId()).isEqualTo("transaction-test");
        assertThat(notice.occurredAt()).isEqualTo(NOW);
        assertThat(notice.payloadHash()).hasSize(64);
        byte[] changed = new String(body, StandardCharsets.UTF_8).replace("{", "{ ").getBytes(StandardCharsets.UTF_8);
        assertThat(mapper.from(changed, "msg_test").orElseThrow().payloadHash()).isNotEqualTo(notice.payloadHash());
    }

    @Test void unrelatedOrUnknownEventTypesAreIgnoredWithoutInventingPaymentWork() {
        assertThat(mapper.from("{\"type\":\"BillingKey.Issued\"}".getBytes(StandardCharsets.UTF_8), "msg_test")).isEmpty();
        assertThat(mapper.from(body("Transaction.FutureEvent", STORE), "msg_test")).isEmpty();
    }

    @Test void wrongStoreMissingIdentifiersMalformedTimeAndDuplicateJsonFieldsAreRejected() {
        assertThatThrownBy(() -> mapper.from(body("Transaction.Paid", "store-other"), "msg_test"))
                .isInstanceOf(IllegalArgumentException.class);
        String original = new String(body("Transaction.Paid", STORE), StandardCharsets.UTF_8);
        for (String changed : new String[] {
                original.replace("payment-test", ""), original.replace(NOW.toString(), "bad-time"),
                original.replace("\"paymentId\":\"payment-test\"", "\"paymentId\":\"one\",\"paymentId\":\"two\"")}) {
            assertThatThrownBy(() -> mapper.from(changed.getBytes(StandardCharsets.UTF_8), "msg_test"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static byte[] body(String type, String store) {
        return ("{\"type\":\"" + type + "\",\"timestamp\":\"" + NOW + "\",\"data\":{\"storeId\":\"" + store
                + "\",\"paymentId\":\"payment-test\",\"transactionId\":\"transaction-test\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }
}

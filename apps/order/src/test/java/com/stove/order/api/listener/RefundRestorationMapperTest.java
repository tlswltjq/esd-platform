package com.stove.order.api.listener;

import static com.stove.order.OrderFixtures.envelope;
import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.RefundExecutionFailedEvent;
import com.stove.common.kafka.contract.payload.LicenseRestoredEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundRestorationMapperTest {
    private final RefundExecutionFailedEvent failed = new RefundExecutionFailedEvent(id(), Instant.parse("2026-10-27T01:02:00Z"), id(), id(), id(),
            id(), id(), "buyer", 9000, "KRW", "pg-original", "cancel-own", "a".repeat(64));
    private final LicenseRestoredEvent restored = new LicenseRestoredEvent(id(), Instant.parse("2026-10-27T01:03:00Z"), id(), failed.refundId(), failed.attemptId(),
            failed.eventId(), id(), failed.orderNo(), failed.buyerSubject(), 1L, "PRODUCT-1", failed.paymentId(), id(), id());
    @Test void mapsFailureAndRestorationWithAllTheirDifferentCorrelationKeys() {
        assertThat(RefundFailureConfirmationMapper.from(envelope(failed), failed).lookupHash()).isEqualTo(failed.lookupHash());
        assertThat(LicenseRestorationConfirmationMapper.from(envelope(restored), restored).failureEventId()).isEqualTo(failed.eventId());
    }
    @Test void wrongEnvelopeIdsTypesAndKeysNeverReachInternalState() {
        for (EventEnvelope invalid : List.of(new EventEnvelope(id(), failed.eventType(), failed.orderNo(), ""),
                new EventEnvelope(failed.eventId(), "Other", failed.orderNo(), ""), new EventEnvelope(failed.eventId(), failed.eventType(), id(), "")))
            assertThatThrownBy(() -> RefundFailureConfirmationMapper.from(invalid, failed)).isInstanceOf(RefundCompletionRejected.class);
        assertThatThrownBy(() -> LicenseRestorationConfirmationMapper.from(new EventEnvelope(restored.eventId(), restored.eventType(), id(), ""), restored))
                .isInstanceOf(RefundCompletionRejected.class);
    }
    @Test void missingFinancialProofCannotBeInventedFromAFailureFlag() {
        RefundExecutionFailedEvent invalid = new RefundExecutionFailedEvent(failed.eventId(), failed.occurredAt(), failed.refundId(), failed.attemptId(),
                failed.requestEventId(), failed.orderNo(), failed.paymentId(), failed.buyerSubject(), failed.amount(), failed.currency(), failed.providerTransactionId(), failed.cancellationId(), null);
        assertThatThrownBy(() -> RefundFailureConfirmationMapper.from(envelope(invalid), invalid)).isInstanceOf(RefundCompletionRejected.class);
    }
}

package com.stove.order.api.listener;

import static com.stove.order.OrderFixtures.envelope;
import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.RefundExecutionFailedEvent;
import com.stove.common.kafka.contract.payload.RefundFailureClosedEvent;
import com.stove.common.kafka.contract.payload.LicenseRestoredEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.RefundFailureSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundRestorationMapperTest {
    private final RefundExecutionFailedEvent failed = new RefundExecutionFailedEvent(id(), Instant.parse("2026-10-27T01:02:00Z"), id(), id(), id(),
            id(), id(), "buyer", 9000, "KRW", "pg-original", "cancel-own", "a".repeat(64));
    private final LicenseRestoredEvent restored = new LicenseRestoredEvent(id(), Instant.parse("2026-10-27T01:03:00Z"), id(), failed.refundId(), failed.attemptId(),
            failed.eventId(), id(), failed.orderNo(), failed.buyerSubject(), 1L, "PRODUCT-1", failed.paymentId(), id(), id(), true);
    @Test void mapsFailureAndRestorationWithAllTheirDifferentCorrelationKeys() {
        assertThat(RefundFailureConfirmationMapper.from(envelope(failed), failed).lookupHash()).isEqualTo(failed.lookupHash());
        assertThat(LicenseRestorationConfirmationMapper.from(envelope(restored), restored).failureEventId()).isEqualTo(failed.eventId());
        assertThat(LicenseRestorationConfirmationMapper.from(envelope(restored), restored).returnedToStorage()).isTrue();
    }
    @Test void wrongEnvelopeIdsTypesAndKeysNeverReachInternalState() {
        for (EventEnvelope invalid : List.of(new EventEnvelope(id(), failed.eventType(), failed.orderNo(), ""),
                new EventEnvelope(failed.eventId(), "Other", failed.orderNo(), ""), new EventEnvelope(failed.eventId(), failed.eventType(), id(), "")))
            assertThatThrownBy(() -> RefundFailureConfirmationMapper.from(invalid, failed)).isInstanceOf(RefundCompletionRejected.class);
        assertThatThrownBy(() -> LicenseRestorationConfirmationMapper.from(new EventEnvelope(restored.eventId(), restored.eventType(), id(), ""), restored))
                .isInstanceOf(RefundCompletionRejected.class);
    }
    private final RefundFailureClosedEvent closed = new RefundFailureClosedEvent(id(), Instant.parse("2026-10-27T01:02:00.123456Z"), id(), id(), id(),
            id(), id(), "buyer", 9000, "KRW", "pg-original", id(), "PAID", Instant.parse("2026-10-27T01:01:59.123456Z"), "b".repeat(64), null);

    /** 결정 148: 운영자 실패 종결은 PG 확정 실패와 같은 내부 입력으로 바뀌되, 출처·종결 ID·관측 시각을 담는다. */
    @Test void mapsAnOperatorFailureClosureToTheFailureInputWithItsSourceAndClosure() {
        RefundFailureConfirmation input = RefundFailureClosureMapper.from(envelope(closed), closed);
        assertThat(input.source()).isEqualTo(RefundFailureSource.OPERATOR_FAILURE_CLOSED);
        assertThat(input.closureId()).isEqualTo(closed.closureId());
        assertThat(input.cancellationId()).isNull();
        assertThat(input.evidenceObservedAt()).isEqualTo(closed.evidenceObservedAt());
        assertThat(input.occurredAt()).isEqualTo(closed.occurredAt()); assertThat(input.lookupHash()).isEqualTo(closed.lookupHash());
        assertThat(RefundFailureConfirmationMapper.from(envelope(failed), failed).source()).isEqualTo(RefundFailureSource.PG_CANCELLATION_FAILED);
        assertThat(RefundFailureConfirmationMapper.from(envelope(failed), failed).closureId()).isNull();
    }

    @Test void aClosureWithAWrongEnvelopeOrAPaymentThatWasNotUncancelledIsRejected() {
        for (EventEnvelope invalid : List.of(new EventEnvelope(id(), closed.eventType(), closed.orderNo(), ""),
                new EventEnvelope(closed.eventId(), failed.eventType(), closed.orderNo(), ""), new EventEnvelope(closed.eventId(), closed.eventType(), id(), "")))
            assertThatThrownBy(() -> RefundFailureClosureMapper.from(invalid, closed)).isInstanceOf(RefundCompletionRejected.class);
        RefundFailureClosedEvent cancelled = new RefundFailureClosedEvent(closed.eventId(), closed.occurredAt(), closed.refundId(), closed.attemptId(),
                closed.requestEventId(), closed.orderNo(), closed.paymentId(), closed.buyerSubject(), closed.amount(), closed.currency(),
                closed.providerTransactionId(), closed.closureId(), "CANCELLED", closed.evidenceObservedAt(), closed.lookupHash(), null);
        assertThatThrownBy(() -> RefundFailureClosureMapper.from(envelope(cancelled), cancelled)).isInstanceOf(RefundCompletionRejected.class);
        RefundFailureClosedEvent noClosure = new RefundFailureClosedEvent(closed.eventId(), closed.occurredAt(), closed.refundId(), closed.attemptId(),
                closed.requestEventId(), closed.orderNo(), closed.paymentId(), closed.buyerSubject(), closed.amount(), closed.currency(),
                closed.providerTransactionId(), null, "PAID", closed.evidenceObservedAt(), closed.lookupHash(), null);
        assertThatThrownBy(() -> RefundFailureClosureMapper.from(envelope(noClosure), noClosure)).isInstanceOf(RefundCompletionRejected.class);
    }

    @Test void missingFinancialProofCannotBeInventedFromAFailureFlag() {
        RefundExecutionFailedEvent invalid = new RefundExecutionFailedEvent(failed.eventId(), failed.occurredAt(), failed.refundId(), failed.attemptId(),
                failed.requestEventId(), failed.orderNo(), failed.paymentId(), failed.buyerSubject(), failed.amount(), failed.currency(), failed.providerTransactionId(), failed.cancellationId(), null);
        assertThatThrownBy(() -> RefundFailureConfirmationMapper.from(envelope(invalid), invalid)).isInstanceOf(RefundCompletionRejected.class);
    }
}

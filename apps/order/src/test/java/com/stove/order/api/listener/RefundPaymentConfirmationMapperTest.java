package com.stove.order.api.listener;

import static com.stove.order.OrderFixtures.message;
import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundPaymentConfirmationMapperTest {
    private final RefundPaymentConfirmedEvent event = new RefundPaymentConfirmedEvent(id(), Instant.parse("2026-10-27T01:02:03Z"),
            id(), id(), id(), id(), id(), "buyer", 9000, "KRW", "PG_QUERY_UNATTRIBUTED", "pg-original", List.of("cancel-1"));

    @Test void mapsValidatedMoneyConfirmationWithoutAnAttemptSuccessClaim() {
        RefundPaymentConfirmation input = RefundPaymentConfirmationMapper.from(message(event), event);
        assertThat(input.source()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(input.cancellationIds()).containsExactly("cancel-1");
    }

    @Test void changedHeaderIdentityTypeOrKeyIsRejectedBeforeBusinessProcessing() {
        for (EventMessage invalid : List.of(new EventMessage(id(), event.eventType(), event.orderNo(), ""),
                new EventMessage(event.eventId(), "Different", event.orderNo(), ""),
                new EventMessage(event.eventId(), event.eventType(), id(), "")))
            assertThatThrownBy(() -> RefundPaymentConfirmationMapper.from(invalid, event)).isInstanceOf(RefundCompletionRejected.class);
    }

    @Test void unsupportedSourceAndDuplicateCancellationIdentitiesAreRejectedAtTheBoundary() {
        for (RefundPaymentConfirmedEvent invalid : List.of(copy("ATTEMPT_SUCCEEDED", List.of("cancel-1")),
                copy(event.source(), List.of("cancel-1", "cancel-1")), copy(event.source(), List.of())))
            assertThatThrownBy(() -> RefundPaymentConfirmationMapper.from(message(invalid), invalid)).isInstanceOf(RefundCompletionRejected.class);
    }

    private RefundPaymentConfirmedEvent copy(String source, List<String> ids) {
        return new RefundPaymentConfirmedEvent(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(),
                event.requestEventId(), event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(), event.currency(),
                source, event.providerTransactionId(), ids);
    }
}

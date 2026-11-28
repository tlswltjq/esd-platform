package com.stove.order.api.listener;

import static com.stove.order.OrderFixtures.message;
import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.RefundExecutionSucceededEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundExecutionSuccessMapperTest {
    private final RefundExecutionSucceededEvent event = new RefundExecutionSucceededEvent(id(), Instant.parse("2026-10-27T01:00:00Z"),
            id(), id(), id(), id(), id(), id(), "buyer", 9000, "KRW", "pg-original", "cancel-1");

    @Test void preservesTheFirstMoneyEventLinkWhileAttributingOnlyThisExecution() {
        assertThat(RefundExecutionSuccessMapper.from(message(event), event).confirmationEventId()).isEqualTo(event.confirmationEventId());
        assertThat(RefundExecutionSuccessMapper.from(message(event), event).execution().source()).isEqualTo("PG_RESPONSE");
    }

    @Test void messageKeyAndIdentityCannotBeReusedForAnotherExecution() {
        EventMessage wrong = new EventMessage(event.eventId(), event.eventType(), id(), "");
        assertThatThrownBy(() -> RefundExecutionSuccessMapper.from(wrong, event)).isInstanceOf(RefundCompletionRejected.class);
    }

    @Test void aLateSuccessCannotReuseTheMoneyEventIdOrOmitItsCancellationIdentity() {
        RefundExecutionSucceededEvent reused = copy(event.eventId(), "cancel-1");
        RefundExecutionSucceededEvent missing = copy(event.confirmationEventId(), null);
        assertThatThrownBy(() -> RefundExecutionSuccessMapper.from(message(reused), reused)).isInstanceOf(RefundCompletionRejected.class);
        assertThatThrownBy(() -> RefundExecutionSuccessMapper.from(message(missing), missing)).isInstanceOf(RefundCompletionRejected.class);
    }

    private RefundExecutionSucceededEvent copy(String confirmationId, String cancellationId) {
        return new RefundExecutionSucceededEvent(event.eventId(), event.occurredAt(), confirmationId, event.refundId(), event.attemptId(),
                event.requestEventId(), event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(), event.currency(),
                event.providerTransactionId(), cancellationId);
    }
}

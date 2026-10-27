package com.stove.order.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.RefundExecutionFailedEvent;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.util.Objects;

public final class RefundFailureConfirmationMapper {
    private RefundFailureConfirmationMapper() { }
    public static RefundFailureConfirmation from(EventEnvelope envelope, RefundExecutionFailedEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo()))
            throw new RefundCompletionRejected("실패/복구 봉투·본문·키가 다릅니다.");
        return new RefundFailureConfirmation(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(), event.requestEventId(), event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(), event.currency(), event.providerTransactionId(), event.cancellationId(), event.lookupHash());
    }
}

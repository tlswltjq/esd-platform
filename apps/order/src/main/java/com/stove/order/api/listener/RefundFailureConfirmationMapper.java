package com.stove.order.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.RefundExecutionFailedEvent;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.util.Objects;

public final class RefundFailureConfirmationMapper {
    private RefundFailureConfirmationMapper() { }
    public static RefundFailureConfirmation from(EventMessage message, RefundExecutionFailedEvent event) {
        if (message == null || event == null || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType()) || !Objects.equals(message.key(), event.orderNo()))
            throw new RefundCompletionRejected("실패/복구 헤더·본문·키가 다릅니다.");
        return new RefundFailureConfirmation(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(), event.requestEventId(), event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(), event.currency(), event.providerTransactionId(), event.cancellationId(), event.lookupHash());
    }
}

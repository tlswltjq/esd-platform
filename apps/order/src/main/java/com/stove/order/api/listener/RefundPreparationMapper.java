package com.stove.order.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.RefundExecutionPreparedEvent;
import com.stove.order.core.domain.RefundPreparationConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.util.Objects;

public final class RefundPreparationMapper {
    private RefundPreparationMapper() { }
    public static RefundPreparationConfirmation from(EventEnvelope envelope, RefundExecutionPreparedEvent event) {
        if (envelope==null || event==null || !Objects.equals(envelope.eventId(),event.eventId())
                || !Objects.equals(envelope.eventType(),event.eventType()) || !Objects.equals(envelope.key(),event.orderNo()))
            throw new RefundCompletionRejected("준비 이벤트의 봉투·본문·키가 다릅니다.");
        return new RefundPreparationConfirmation(event.eventId(),event.occurredAt(),event.refundId(),event.attemptId(),event.requestEventId(),event.orderNo(),
                event.paymentId(),event.buyerSubject(),event.amount(),event.currency(),event.licenseSuspensionEventId(),event.previousAttemptId(),event.firstPreparedAt(),event.confirmationDelayAt());
    }
}

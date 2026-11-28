package com.stove.order.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.RefundExecutionPreparedEvent;
import com.stove.order.core.domain.RefundPreparationConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.util.Objects;

public final class RefundPreparationMapper {
    private RefundPreparationMapper() { }
    public static RefundPreparationConfirmation from(EventMessage message, RefundExecutionPreparedEvent event) {
        if (message==null || event==null || !Objects.equals(message.eventId(),event.eventId())
                || !Objects.equals(message.eventType(),event.eventType()) || !Objects.equals(message.key(),event.orderNo()))
            throw new RefundCompletionRejected("준비 이벤트의 헤더·본문·키가 다릅니다.");
        return new RefundPreparationConfirmation(event.eventId(),event.occurredAt(),event.refundId(),event.attemptId(),event.requestEventId(),event.orderNo(),
                event.paymentId(),event.buyerSubject(),event.amount(),event.currency(),event.licenseSuspensionEventId(),event.previousAttemptId(),event.firstPreparedAt(),event.confirmationDelayAt());
    }
}

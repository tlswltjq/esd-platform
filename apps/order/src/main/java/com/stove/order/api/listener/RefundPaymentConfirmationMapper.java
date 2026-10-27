package com.stove.order.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import java.util.Objects;

public final class RefundPaymentConfirmationMapper {
    private RefundPaymentConfirmationMapper() { }

    public static RefundPaymentConfirmation from(EventEnvelope envelope, RefundPaymentConfirmedEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo()))
            throw new RefundCompletionRejected("전액 취소 확인의 봉투·본문·키가 다릅니다.");
        return new RefundPaymentConfirmation(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(),
                event.requestEventId(), event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(), event.currency(),
                event.source(), event.providerTransactionId(), event.cancellationIds());
    }
}

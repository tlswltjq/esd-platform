package com.stove.order.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.RefundExecutionSucceededEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundExecutionSuccess;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import java.util.List;
import java.util.Objects;

public final class RefundExecutionSuccessMapper {
    private RefundExecutionSuccessMapper() { }
    public static RefundExecutionSuccess from(EventEnvelope envelope, RefundExecutionSucceededEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo())
                || event.cancellationId() == null || Objects.equals(event.eventId(), event.confirmationEventId()))
            throw new RefundCompletionRejected("실행 성공 봉투·본문·키가 다릅니다.");
        return new RefundExecutionSuccess(new RefundPaymentConfirmation(event.eventId(), event.occurredAt(), event.refundId(),
                event.attemptId(), event.requestEventId(), event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(),
                event.currency(), "PG_RESPONSE", event.providerTransactionId(), List.of(event.cancellationId())), event.confirmationEventId());
    }
}

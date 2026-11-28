package com.stove.order.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.RefundExecutionSucceededEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundExecutionSuccess;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import java.util.List;
import java.util.Objects;

public final class RefundExecutionSuccessMapper {
    private RefundExecutionSuccessMapper() { }
    public static RefundExecutionSuccess from(EventMessage message, RefundExecutionSucceededEvent event) {
        if (message == null || event == null || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType()) || !Objects.equals(message.key(), event.orderNo())
                || event.cancellationId() == null || Objects.equals(event.eventId(), event.confirmationEventId()))
            throw new RefundCompletionRejected("실행 성공 헤더·본문·키가 다릅니다.");
        return new RefundExecutionSuccess(new RefundPaymentConfirmation(event.eventId(), event.occurredAt(), event.refundId(),
                event.attemptId(), event.requestEventId(), event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(),
                event.currency(), "PG_RESPONSE", event.providerTransactionId(), List.of(event.cancellationId()),
                RefundAmountMapper.from(event.amountDetails(), event.amount())), event.confirmationEventId());
    }
}

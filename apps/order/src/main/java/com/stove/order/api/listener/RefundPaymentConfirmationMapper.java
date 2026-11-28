package com.stove.order.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import java.util.Objects;

public final class RefundPaymentConfirmationMapper {
    private RefundPaymentConfirmationMapper() { }

    public static RefundPaymentConfirmation from(EventMessage message, RefundPaymentConfirmedEvent event) {
        if (message == null || event == null || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType()) || !Objects.equals(message.key(), event.orderNo()))
            throw new RefundCompletionRejected("전액 취소 확인의 헤더·본문·키가 다릅니다.");
        return new RefundPaymentConfirmation(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(),
                event.requestEventId(), event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(), event.currency(),
                event.source(), event.providerTransactionId(), event.cancellationIds(), RefundAmountMapper.from(event.amountDetails(), event.amount()));
    }
}

package com.stove.order.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.DomainEvent;
import com.stove.common.contract.payload.OrderPaymentCompletedEvent;
import com.stove.common.contract.payload.OrderPaymentFailedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.ProviderPaymentAmounts;
import java.util.Objects;

public final class PaymentResultMapper {
    private PaymentResultMapper() {}

    public static PaymentResult from(EventMessage message, OrderPaymentCompletedEvent event) {
        validate(message, event);
        return new PaymentResult(event.eventId(), event.occurredAt(), event.orderNo(),
                event.requestEventId(), event.paymentId(), event.buyerSubject(), event.amount(),
                event.currency(), OrderStatus.PAID, null, null,
                ProviderPaymentAmounts.fromNullable(event.pgPaidAmount(), event.pgDiscountAmount()));
    }

    public static PaymentResult from(EventMessage message, OrderPaymentFailedEvent event) {
        validate(message, event);
        return new PaymentResult(event.eventId(), event.occurredAt(), event.orderNo(),
                event.requestEventId(), event.paymentId(), event.buyerSubject(), event.amount(),
                event.currency(), OrderStatus.FAILED, event.reasonCode(), event.reason());
    }

    private static void validate(EventMessage message, DomainEvent event) {
        if (message == null || event == null
                || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType())
                || !Objects.equals(message.key(), event.partitionKey()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 결과 이벤트 헤더와 본문이 다릅니다.");
    }
}

package com.stove.order.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.web.error.*;
import com.stove.order.core.domain.*;
import java.util.Objects;

public final class PaymentResultMapper {
    private PaymentResultMapper() {}

    public static PaymentResult from(EventEnvelope envelope, OrderPaymentCompletedEvent event) {
        validate(envelope, event);
        return new PaymentResult(event.eventId(), event.occurredAt(), event.orderNo(),
                event.requestEventId(), event.paymentId(), event.buyerSubject(), event.amount(),
                event.currency(), OrderStatus.PAID, null, null);
    }

    public static PaymentResult from(EventEnvelope envelope, OrderPaymentFailedEvent event) {
        validate(envelope, event);
        return new PaymentResult(event.eventId(), event.occurredAt(), event.orderNo(),
                event.requestEventId(), event.paymentId(), event.buyerSubject(), event.amount(),
                event.currency(), OrderStatus.FAILED, event.reasonCode(), event.reason());
    }

    private static void validate(EventEnvelope envelope, DomainEvent event) {
        if (envelope == null || event == null
                || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || !Objects.equals(envelope.key(), event.partitionKey()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 결과 이벤트 봉투와 본문이 다릅니다.");
    }
}

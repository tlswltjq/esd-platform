package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.common.contract.payload.RefundExecutionSucceededEvent;
import com.stove.order.core.domain.RefundExecutionSuccess;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import com.stove.order.core.service.ApplyRefundPaymentConfirmation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.PAYMENT, group = ApplyRefundPaymentConfirmation.CONSUMER_GROUP, noRetryFor = RefundCompletionRejected.class)
public class RefundPaymentConfirmationEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyRefundPaymentConfirmation apply;

    @Override
    public void receive(InboundMessage message) {
        RefundPaymentConfirmation input = null;
        RefundExecutionSuccess success = null;
        try {
            EventEnvelope envelope = EventEnvelope.from(message);
            if (envelope.isType(EventType.REFUND_PAYMENT_CONFIRMED))
                input = RefundPaymentConfirmationMapper.from(envelope, envelope.payloadAs(mapper, RefundPaymentConfirmedEvent.class));
            else if (envelope.isType(EventType.REFUND_EXECUTION_SUCCEEDED))
                success = RefundExecutionSuccessMapper.from(envelope, envelope.payloadAs(mapper, RefundExecutionSucceededEvent.class));
            else return;
        } catch (IllegalStateException malformed) {
            throw new RefundCompletionRejected("전액 취소 확인 메시지 계약이 올바르지 않습니다.", malformed);
        }
        if (success != null) apply.execute(success); else apply.execute(input);
    }
}

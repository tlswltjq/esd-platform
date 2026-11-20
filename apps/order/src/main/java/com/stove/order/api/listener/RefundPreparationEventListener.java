package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.RefundExecutionPreparedEvent;
import com.stove.order.core.domain.RefundPreparationConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.service.ApplyRefundPreparation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.PAYMENT, group = ApplyRefundPreparation.CONSUMER_GROUP, noRetryFor = RefundCompletionRejected.class)
public class RefundPreparationEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyRefundPreparation apply;
    @Override
    public void receive(InboundMessage message) {
        RefundPreparationConfirmation input;
        try {
            EventEnvelope envelope=EventEnvelope.from(message);
            if (!envelope.isType(EventType.REFUND_EXECUTION_PREPARED)) return;
            if (!Topics.PAYMENT.equals(message.topic())) throw new RefundCompletionRejected("준비 이벤트 발행 토픽이 다릅니다.");
            input=RefundPreparationMapper.from(envelope,envelope.payloadAs(mapper,RefundExecutionPreparedEvent.class));
        } catch (IllegalStateException malformed) { throw new RefundCompletionRejected("준비 이벤트 계약이 올바르지 않습니다.",malformed); }
        apply.execute(input);
    }
}

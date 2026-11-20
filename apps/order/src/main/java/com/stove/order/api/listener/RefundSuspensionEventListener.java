package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.LicenseSuspendedEvent;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.service.ApplyRefundSuspension;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.LICENSE, group = ApplyRefundSuspension.CONSUMER_GROUP, noRetryFor = RefundCompletionRejected.class)
public class RefundSuspensionEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyRefundSuspension apply;

    @Override
    public void receive(InboundMessage message) {
        LicenseSuspensionConfirmation input;
        try {
            EventEnvelope envelope = EventEnvelope.from(message);
            if (!envelope.isType(EventType.LICENSE_SUSPENDED)) return;
            input = RefundSuspensionMapper.from(envelope, envelope.payloadAs(mapper, LicenseSuspendedEvent.class));
        } catch (IllegalStateException malformed) {
            throw new RefundCompletionRejected("정지 완료 메시지 계약이 올바르지 않습니다.", malformed);
        }
        apply.execute(input);
    }
}

package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.PurchaseRefundedEvent;
import com.stove.license.core.service.ApplyPurchaseRefund;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.ORDER, group = ApplyPurchaseRefund.CONSUMER_GROUP)
public class PurchaseRefundEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyPurchaseRefund apply;

    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType(EventType.PURCHASE_REFUNDED)) {
            apply.execute(PurchaseRefundMapper.from(envelope, envelope.payloadAs(mapper, PurchaseRefundedEvent.class)));
        }
    }
}

package com.stove.settlement.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.settlement.core.service.RecordConfirmedRefund;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.PAYMENT, group = RecordConfirmedRefund.CONSUMER_GROUP)
public class RefundEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final RecordConfirmedRefund refunds;
    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType(EventType.REFUND_PAYMENT_CONFIRMED))
            refunds.execute(SettlementRefundMapper.from(envelope, envelope.payloadAs(mapper, RefundPaymentConfirmedEvent.class)));
    }
}

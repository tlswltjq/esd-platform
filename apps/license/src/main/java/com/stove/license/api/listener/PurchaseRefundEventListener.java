package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.PurchaseRefundedEvent;
import com.stove.license.core.service.ApplyPurchaseRefund;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Component @RequiredArgsConstructor
public class PurchaseRefundEventListener {
    private final ObjectMapper mapper;
    private final ApplyPurchaseRefund apply;

    @KafkaListener(topics = Topics.ORDER, groupId = ApplyPurchaseRefund.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.PURCHASE_REFUNDED)) {
            apply.execute(PurchaseRefundMapper.from(envelope, envelope.payloadAs(mapper, PurchaseRefundedEvent.class)));
        }
    }
}

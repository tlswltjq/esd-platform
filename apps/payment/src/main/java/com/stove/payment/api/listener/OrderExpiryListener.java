package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderExpiredEvent;
import com.stove.payment.core.service.ApplyOrderExpiry;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderExpiryListener {
    private final ObjectMapper mapper;
    private final ApplyOrderExpiry apply;
    @KafkaListener(topics=Topics.ORDER,groupId=ApplyOrderExpiry.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String,String> record) {
        EventEnvelope envelope=EventEnvelope.from(record);
        if(envelope.isType(EventType.ORDER_EXPIRED))apply.execute(OrderExpiryMapper.from(envelope,envelope.payloadAs(mapper,OrderExpiredEvent.class)));
    }
}

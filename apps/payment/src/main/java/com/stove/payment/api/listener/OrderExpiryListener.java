package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.OrderExpiredEvent;
import com.stove.payment.core.service.ApplyOrderExpiry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Component
@RequiredArgsConstructor
public class OrderExpiryListener {
    private final ObjectMapper mapper;
    private final ApplyOrderExpiry apply;
    @KafkaListener(topics = Topics.ORDER, groupId = ApplyOrderExpiry.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventMessage message=EventMessage.from(record);
        if(message.isType(EventType.ORDER_EXPIRED))apply.execute(OrderExpiryMapper.from(message,message.payloadAs(mapper,OrderExpiredEvent.class)));
    }
}

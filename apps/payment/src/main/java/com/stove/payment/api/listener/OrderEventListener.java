package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import com.stove.payment.core.service.ReceiveOrderPayment;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OrderEventListener {
    private final ObjectMapper mapper;
    private final ReceiveOrderPayment receiveOrderPayment;
    @KafkaListener(topics=Topics.ORDER,groupId=ReceiveOrderPayment.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String,String> record) {
        EventEnvelope envelope=EventEnvelope.from(record);
        if (envelope.isType(EventType.ORDER_PLACED))
            receiveOrderPayment.execute(OrderPaymentMapper.from(envelope,envelope.payloadAs(mapper,OrderPlacedEvent.class)));
    }
}

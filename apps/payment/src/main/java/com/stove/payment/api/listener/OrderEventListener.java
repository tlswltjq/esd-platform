package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventHeaders;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.OrderPlacedEvent;
import com.stove.payment.core.service.ReceiveOrderPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Component @RequiredArgsConstructor
public class OrderEventListener {
    private final ObjectMapper mapper;
    private final ReceiveOrderPayment receiveOrderPayment;
    @KafkaListener(topics = Topics.ORDER, groupId = ReceiveOrderPayment.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope envelope=EventEnvelope.from(record);
        if (envelope.isType(EventType.ORDER_PLACED))
            receiveOrderPayment.execute(OrderPaymentMapper.from(envelope,envelope.payloadAs(mapper,OrderPlacedEvent.class)),
                    PaymentTraceMapper.from(EventHeaders.values(record.headers(), EventHeaders.TRACE_PARENT)));
    }
}

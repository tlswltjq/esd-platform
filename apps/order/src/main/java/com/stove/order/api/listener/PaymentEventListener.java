package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.*;
import com.stove.common.kafka.contract.payload.*;
import com.stove.order.core.service.ApplyPaymentResult;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class PaymentEventListener {
    private final ObjectMapper mapper;
    private final ApplyPaymentResult applyPaymentResult;
    @KafkaListener(topics = Topics.PAYMENT, groupId = ApplyPaymentResult.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        var envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.ORDER_PAYMENT_COMPLETED))
            applyPaymentResult.execute(envelope.eventType(), PaymentResultMapper.from(
                    envelope, envelope.payloadAs(mapper, OrderPaymentCompletedEvent.class)));
        else if (envelope.isType(EventType.ORDER_PAYMENT_FAILED))
            applyPaymentResult.execute(envelope.eventType(), PaymentResultMapper.from(
                    envelope, envelope.payloadAs(mapper, OrderPaymentFailedEvent.class)));
    }
}

package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.OrderPaymentCompletedEvent;
import com.stove.common.contract.payload.OrderPaymentFailedEvent;
import com.stove.order.core.service.ApplyPaymentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Component @RequiredArgsConstructor
public class PaymentEventListener {
    private final ObjectMapper mapper;
    private final ApplyPaymentResult applyPaymentResult;
    @KafkaListener(topics = Topics.PAYMENT, groupId = ApplyPaymentResult.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.ORDER_PAYMENT_COMPLETED))
            applyPaymentResult.execute(envelope.eventType(), PaymentResultMapper.from(
                    envelope, envelope.payloadAs(mapper, OrderPaymentCompletedEvent.class)));
        else if (envelope.isType(EventType.ORDER_PAYMENT_FAILED))
            applyPaymentResult.execute(envelope.eventType(), PaymentResultMapper.from(
                    envelope, envelope.payloadAs(mapper, OrderPaymentFailedEvent.class)));
    }
}

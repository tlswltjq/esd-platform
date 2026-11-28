package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventMessage;
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
        EventMessage message = EventMessage.from(record);
        if (message.isType(EventType.ORDER_PAYMENT_COMPLETED))
            applyPaymentResult.execute(message.eventType(), PaymentResultMapper.from(
                    message, message.payloadAs(mapper, OrderPaymentCompletedEvent.class)));
        else if (message.isType(EventType.ORDER_PAYMENT_FAILED))
            applyPaymentResult.execute(message.eventType(), PaymentResultMapper.from(
                    message, message.payloadAs(mapper, OrderPaymentFailedEvent.class)));
    }
}

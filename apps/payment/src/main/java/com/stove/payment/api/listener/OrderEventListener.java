package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.OrderCreatedEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.payment.core.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** [구매] order → OrderCreated → payment (결제 대기 레코드 생성) */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private static final String GROUP = "payment";

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.ORDER, groupId = GROUP)
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(EventType.ORDER_CREATED)) {
            return;
        }
        OrderCreatedEvent event = envelope.payloadAs(objectMapper, OrderCreatedEvent.class);
        paymentService.createReady(envelope.eventId(), envelope.eventType(), event.orderNo(),
                event.memberId(), event.totalAmount(), "KRW", event.lines());
    }
}

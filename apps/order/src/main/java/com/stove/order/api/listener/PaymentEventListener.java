package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.order.core.service.OrderCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * [결제] payment → PaymentCompleted → order(확정)
 * [환불] payment → PaymentCancelled → order(취소)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final OrderCommandService orderCommandService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.PAYMENT, groupId = OrderCommandService.CONSUMER_GROUP)
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);

        if (envelope.isType(EventType.PAYMENT_COMPLETED)) {
            PaymentCompletedEvent event = envelope.payloadAs(objectMapper, PaymentCompletedEvent.class);
            orderCommandService.confirmPaid(envelope.eventId(), envelope.eventType(), event.orderNo());

        } else if (envelope.isType(EventType.PAYMENT_CANCELLED)) {
            PaymentCancelledEvent event = envelope.payloadAs(objectMapper, PaymentCancelledEvent.class);
            orderCommandService.confirmCanceled(envelope.eventId(), envelope.eventType(),
                    event.orderNo(), event.reason());
        }
    }
}

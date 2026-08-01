package com.stove.order.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.order.application.OrderCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [결제] payment → PaymentCompleted → order(확정)
 * [환불] payment → PaymentCancelled → order(취소)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private static final String GROUP = "order";

    private final OrderCommandService orderCommandService;
    private final ProcessedEventGuard processedEventGuard;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(topics = Topics.PAYMENT, groupId = GROUP)
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);

        if (envelope.isType(EventType.PAYMENT_COMPLETED)) {
            if (!processedEventGuard.firstDelivery(envelope.eventId(), GROUP, envelope.eventType())) {
                return;
            }
            PaymentCompletedEvent event = envelope.payloadAs(objectMapper, PaymentCompletedEvent.class);
            orderCommandService.confirmPaid(event.orderNo());
            log.info("주문 결제 확정 orderNo={}", event.orderNo());

        } else if (envelope.isType(EventType.PAYMENT_CANCELLED)) {
            if (!processedEventGuard.firstDelivery(envelope.eventId(), GROUP, envelope.eventType())) {
                return;
            }
            PaymentCancelledEvent event = envelope.payloadAs(objectMapper, PaymentCancelledEvent.class);
            orderCommandService.confirmCanceled(event.orderNo(), event.reason());
            log.info("주문 취소 반영 orderNo={} reason={}", event.orderNo(), event.reason());
        }
    }
}

package com.stove.settlement.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.settlement.application.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [결제] payment → PaymentCompleted → settlement (집계)
 * [환불] payment → PaymentCancelled → settlement (역산)
 *
 * <p>금전 원장이므로 Inbox 멱등 가드와 원장 유니크 제약을 <b>둘 다</b> 건다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private static final String GROUP = "settlement";

    private final SettlementService settlementService;
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
            settlementService.recordSale(event.orderNo(), event.lines());

        } else if (envelope.isType(EventType.PAYMENT_CANCELLED)) {
            if (!processedEventGuard.firstDelivery(envelope.eventId(), GROUP, envelope.eventType())) {
                return;
            }
            PaymentCancelledEvent event = envelope.payloadAs(objectMapper, PaymentCancelledEvent.class);
            settlementService.recordRefund(event.orderNo());
        }
    }
}

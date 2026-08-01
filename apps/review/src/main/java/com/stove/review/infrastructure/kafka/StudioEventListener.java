package com.stove.review.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.GameRegisteredEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.review.application.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** [등록] studio → GameRegistered → review */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudioEventListener {

    private static final String GROUP = "review";

    private final ReviewService reviewService;
    private final ProcessedEventGuard processedEventGuard;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(topics = Topics.STUDIO, groupId = GROUP)
    public void onStudioEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(EventType.GAME_REGISTERED)) {
            return; // BuildUploaded 는 download 담당
        }
        if (!processedEventGuard.firstDelivery(envelope.eventId(), GROUP, envelope.eventType())) {
            return;
        }
        reviewService.receive(envelope.payloadAs(objectMapper, GameRegisteredEvent.class));
    }
}

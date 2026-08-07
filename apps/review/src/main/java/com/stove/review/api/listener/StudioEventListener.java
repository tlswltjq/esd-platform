package com.stove.review.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.GameRegisteredEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.review.core.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * [등록] studio → GameRegistered → review
 *
 * <p>트랜잭션과 멱등 마킹은 {@link ReviewService} 가 소유한다. 이 어댑터는 봉투만 푼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudioEventListener {

    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.STUDIO, groupId = ReviewService.CONSUMER_GROUP)
    public void onStudioEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(EventType.GAME_REGISTERED)) {
            return; // BuildUploaded 는 download 담당
        }
        reviewService.receive(envelope.eventId(), envelope.eventType(),
                envelope.payloadAs(objectMapper, GameRegisteredEvent.class));
    }
}

package com.stove.studio.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.ReviewApprovedEvent;
import com.stove.common.event.payload.ReviewRejectedEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.studio.core.service.GameProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 심의 결과를 창작자 화면(프로젝트 상태)에 반영한다.
 *
 * <p>트랜잭션과 멱등 마킹은 {@link GameProjectService} 가 소유한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewEventListener {

    private final GameProjectService gameProjectService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.REVIEW, groupId = GameProjectService.CONSUMER_GROUP)
    public void onReviewEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);

        if (envelope.isType(EventType.REVIEW_APPROVED)) {
            ReviewApprovedEvent event = envelope.payloadAs(objectMapper, ReviewApprovedEvent.class);
            gameProjectService.applyApproval(envelope.eventId(), envelope.eventType(),
                    event.productCode(), event.ratingCode());

        } else if (envelope.isType(EventType.REVIEW_REJECTED)) {
            ReviewRejectedEvent event = envelope.payloadAs(objectMapper, ReviewRejectedEvent.class);
            gameProjectService.applyRejection(envelope.eventId(), envelope.eventType(),
                    event.productCode(), event.reason());
        }
    }
}

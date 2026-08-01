package com.stove.studio.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.ReviewApprovedEvent;
import com.stove.common.event.payload.ReviewRejectedEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.studio.application.StudioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 심의 결과를 창작자 화면(프로젝트 상태)에 반영한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewEventListener {

    private static final String GROUP = "studio-service";

    private final StudioService studioService;
    private final ProcessedEventGuard processedEventGuard;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(topics = Topics.REVIEW, groupId = GROUP)
    public void onReviewEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);

        if (envelope.isType(EventType.REVIEW_APPROVED)) {
            if (!processedEventGuard.firstDelivery(envelope.eventId(), GROUP, envelope.eventType())) {
                return;
            }
            ReviewApprovedEvent event = envelope.payloadAs(objectMapper, ReviewApprovedEvent.class);
            studioService.applyApproval(event.productCode(), event.ratingCode());
            log.info("심의 승인 반영 productCode={} rating={}", event.productCode(), event.ratingCode());

        } else if (envelope.isType(EventType.REVIEW_REJECTED)) {
            if (!processedEventGuard.firstDelivery(envelope.eventId(), GROUP, envelope.eventType())) {
                return;
            }
            ReviewRejectedEvent event = envelope.payloadAs(objectMapper, ReviewRejectedEvent.class);
            studioService.applyRejection(event.productCode(), event.reason());
            log.info("심의 반려 반영 productCode={} reason={}", event.productCode(), event.reason());
        }
    }
}

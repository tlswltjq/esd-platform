package com.stove.catalog.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.application.ProductCommandService;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.ReviewApprovedEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [승인] review → ReviewApproved → catalog (상품 마스터 생성/노출 전환)
 * 멱등 마킹·상태 변경·색인 이벤트 적재를 한 트랜잭션에 묶는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewEventListener {

    private static final String GROUP = "catalog-service";

    private final ProductCommandService productCommandService;
    private final ProcessedEventGuard processedEventGuard;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(topics = Topics.REVIEW, groupId = GROUP)
    public void onReviewEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(EventType.REVIEW_APPROVED)) {
            return; // 반려는 studio 만 처리한다
        }
        if (!processedEventGuard.firstDelivery(envelope.eventId(), GROUP, envelope.eventType())) {
            return;
        }
        productCommandService.upsertFromReview(envelope.payloadAs(objectMapper, ReviewApprovedEvent.class));
    }
}

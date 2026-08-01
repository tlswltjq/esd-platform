package com.stove.catalog.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.service.ProductCommandService;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.event.payload.ReviewApprovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * [승인] review → ReviewApproved → catalog (상품 마스터 생성/노출 전환)
 *
 * <p>이 어댑터는 트랜잭션을 열지 않는다. 멱등 마킹·상태 변경·색인 이벤트 적재를
 * 한 트랜잭션에 묶는 책임은 {@link ProductCommandService} 에 있으며,
 * 어댑터는 봉투를 풀어 전달하는 일만 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewEventListener {

    private static final String GROUP = "catalog";

    private final ProductCommandService productCommandService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.REVIEW, groupId = GROUP)
    public void onReviewEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(EventType.REVIEW_APPROVED)) {
            return; // 반려는 studio 만 처리한다
        }
        productCommandService.upsertFromReview(envelope.eventId(), envelope.eventType(),
                envelope.payloadAs(objectMapper, ReviewApprovedEvent.class));
    }
}

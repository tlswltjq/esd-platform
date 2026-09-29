package com.stove.catalog.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.service.ProductCommandService;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.event.payload.ReleasePublishedEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Catalog 진열 상태를 오직 공개 완료된 릴리스로부터 투영한다. */
@Component
@RequiredArgsConstructor
public class StudioReleaseEventListener {

    private final ProductCommandService productCommandService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.STUDIO, groupId = ProductCommandService.CONSUMER_GROUP)
    public void onStudioEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(EventType.RELEASE_PUBLISHED)) {
            return;
        }
        productCommandService.upsertFromRelease(envelope.eventId(), envelope.eventType(),
                envelope.payloadAs(objectMapper, ReleasePublishedEvent.class));
    }
}

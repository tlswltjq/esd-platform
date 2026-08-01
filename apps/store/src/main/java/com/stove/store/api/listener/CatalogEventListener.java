package com.stove.store.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.store.core.service.StoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * catalog → ProductChanged → 검색 색인 동기화.
 *
 * <p>여기에는 Inbox 테이블이 없다. 색인 연산이 문서 ID 기준 upsert 라
 * 중복 수신해도 결과가 동일하기 때문이다(자연 멱등).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogEventListener {

    private final StoreService storeService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.CATALOG, groupId = "store")
    public void onCatalogEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(EventType.PRODUCT_CHANGED)) {
            return;
        }
        storeService.indexProduct(envelope.payloadAs(objectMapper, ProductChangedEvent.class));
    }
}

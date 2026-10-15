package com.stove.store.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.*;
import com.stove.common.kafka.contract.payload.CatalogProductPublishedEvent;
import com.stove.store.core.service.ApplyCatalogProduct;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CatalogEventListener {
    private final ObjectMapper mapper;
    private final ApplyCatalogProduct applyCatalogProduct;
    @KafkaListener(topics = Topics.CATALOG, groupId = ApplyCatalogProduct.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        var envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.CATALOG_PRODUCT_PUBLISHED))
            applyCatalogProduct.execute(envelope, envelope.payloadAs(mapper, CatalogProductPublishedEvent.class));
    }
}

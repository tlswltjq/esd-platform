package com.stove.store.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.CatalogProductPublishedEvent;
import com.stove.store.core.service.ApplyCatalogProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EventSubscriber(topics = Topics.CATALOG, group = ApplyCatalogProduct.CONSUMER_GROUP)
public class CatalogEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyCatalogProduct applyCatalogProduct;
    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType(EventType.CATALOG_PRODUCT_PUBLISHED))
            applyCatalogProduct.execute(CatalogProductMapper.from(
                    envelope, envelope.payloadAs(mapper, CatalogProductPublishedEvent.class)));
    }
}

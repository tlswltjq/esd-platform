package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.*;
import java.time.Instant;
import java.util.UUID;

public record CatalogProductPublishedEvent(
        String eventId, Instant occurredAt, CatalogProductSnapshot product) implements DomainEvent {
    public static CatalogProductPublishedEvent of(CatalogProductSnapshot product) {
        return new CatalogProductPublishedEvent(UUID.randomUUID().toString(), Instant.now(), product);
    }
    @Override public String eventType() { return EventType.CATALOG_PRODUCT_PUBLISHED; }
    @Override public String topic() { return Topics.CATALOG; }
    @Override public String partitionKey() { return product.productCode(); }
}

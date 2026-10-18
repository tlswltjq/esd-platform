package com.stove.store.api.listener;

import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.CatalogProductPublishedEvent;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class CatalogProductMapperTest {
    @Test
    void requiresMatchingMetadataBeforeReturningTheSharedPublicSnapshot() {
        CatalogProductPublishedEvent event = CatalogProductPublishedEvent.of(product(1, 1, "Game", 1000));
        EventEnvelope envelope = new EventEnvelope(event.eventId(), event.eventType(), event.partitionKey(), "");
        assertThat(CatalogProductMapper.from(envelope, event)).isEqualTo(event.product());
        for (EventEnvelope invalid : new EventEnvelope[]{null,
                new EventEnvelope("other", event.eventType(), event.partitionKey(), ""),
                new EventEnvelope(event.eventId(), "other", event.partitionKey(), ""),
                new EventEnvelope(event.eventId(), event.eventType(), "other", "")})
            assertThatThrownBy(() -> CatalogProductMapper.from(invalid, event))
                    .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CatalogProductMapper.from(envelope, null))
                .isInstanceOf(BusinessException.class);
        CatalogProductPublishedEvent missing = new CatalogProductPublishedEvent(event.eventId(), event.occurredAt(), null);
        assertThatThrownBy(() -> CatalogProductMapper.from(envelope, missing))
                .isInstanceOf(BusinessException.class);
    }
}

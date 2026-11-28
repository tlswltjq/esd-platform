package com.stove.store.api.listener;

import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.CatalogProductPublishedEvent;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class CatalogProductMapperTest {
    @Test
    void requiresMatchingMetadataBeforeReturningTheSharedPublicSnapshot() {
        CatalogProductPublishedEvent event = CatalogProductPublishedEvent.of(product(1, 1, "Game", 1000));
        EventMessage message = new EventMessage(event.eventId(), event.eventType(), event.partitionKey(), "");
        assertThat(CatalogProductMapper.from(message, event)).isEqualTo(event.product());
        for (EventMessage invalid : new EventMessage[]{null,
                new EventMessage("other", event.eventType(), event.partitionKey(), ""),
                new EventMessage(event.eventId(), "other", event.partitionKey(), ""),
                new EventMessage(event.eventId(), event.eventType(), "other", "")})
            assertThatThrownBy(() -> CatalogProductMapper.from(invalid, event))
                    .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CatalogProductMapper.from(message, null))
                .isInstanceOf(BusinessException.class);
        CatalogProductPublishedEvent missing = new CatalogProductPublishedEvent(event.eventId(), event.occurredAt(), null);
        assertThatThrownBy(() -> CatalogProductMapper.from(message, missing))
                .isInstanceOf(BusinessException.class);
    }
}

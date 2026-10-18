package com.stove.catalog.api.listener;

import static com.stove.catalog.CatalogFixtures.envelope;
import static com.stove.catalog.CatalogFixtures.event;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class ReleaseSubmissionMapperTest {
    @Test
    void rejectsUnrelatedEnvelopeMetadataAndMissingSnapshotBeforePublication() {
        ProjectReleaseRequestedEvent event = event(1, 1);
        for (EventEnvelope envelope : new EventEnvelope[]{null,
                new EventEnvelope("other", event.eventType(), event.partitionKey(), ""),
                new EventEnvelope(event.eventId(), "other", event.partitionKey(), ""),
                new EventEnvelope(event.eventId(), event.eventType(), "other", "")})
            assertThatThrownBy(() -> ReleaseSubmissionMapper.from(envelope, event))
                    .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReleaseSubmissionMapper.from(envelope(event), null))
                .isInstanceOf(BusinessException.class);
        ProjectReleaseRequestedEvent missing = new ProjectReleaseRequestedEvent(event.eventId(), event.occurredAt(), 1L, 1L, null);
        assertThatThrownBy(() -> ReleaseSubmissionMapper.from(
                new EventEnvelope(event.eventId(), event.eventType(), event.partitionKey(), ""), missing))
                .isInstanceOf(BusinessException.class);
    }
}

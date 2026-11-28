package com.stove.catalog.api.listener;

import static com.stove.catalog.CatalogFixtures.message;
import static com.stove.catalog.CatalogFixtures.event;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class ReleaseSubmissionMapperTest {
    @Test
    void rejectsUnrelatedHeaderMetadataAndMissingSnapshotBeforePublication() {
        ProjectReleaseRequestedEvent event = event(1, 1);
        for (EventMessage message : new EventMessage[]{null,
                new EventMessage("other", event.eventType(), event.partitionKey(), ""),
                new EventMessage(event.eventId(), "other", event.partitionKey(), ""),
                new EventMessage(event.eventId(), event.eventType(), "other", "")})
            assertThatThrownBy(() -> ReleaseSubmissionMapper.from(message, event))
                    .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReleaseSubmissionMapper.from(message(event), null))
                .isInstanceOf(BusinessException.class);
        ProjectReleaseRequestedEvent missing = new ProjectReleaseRequestedEvent(event.eventId(), event.occurredAt(), 1L, 1L, null);
        assertThatThrownBy(() -> ReleaseSubmissionMapper.from(
                new EventMessage(event.eventId(), event.eventType(), event.partitionKey(), ""), missing))
                .isInstanceOf(BusinessException.class);
    }
}

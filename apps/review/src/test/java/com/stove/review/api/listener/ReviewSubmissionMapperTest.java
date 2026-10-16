package com.stove.review.api.listener;

import static com.stove.review.ReviewFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class ReviewSubmissionMapperTest {
    @Test
    void rejectsUnrelatedEnvelopeMetadataAndMissingSubmissionBeforeIntake() {
        var event = event(1, 1);
        for (var envelope : new EventEnvelope[]{null,
                new EventEnvelope("other", event.eventType(), event.partitionKey(), ""),
                new EventEnvelope(event.eventId(), "other", event.partitionKey(), ""),
                new EventEnvelope(event.eventId(), event.eventType(), "other", "")})
            assertThatThrownBy(() -> ReviewSubmissionMapper.from(envelope, event))
                    .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReviewSubmissionMapper.from(envelope(event), null))
                .isInstanceOf(BusinessException.class);
        var missing = new ProjectReviewRequestedEvent(event.eventId(), event.occurredAt(), 1L, null);
        assertThatThrownBy(() -> ReviewSubmissionMapper.from(envelope(event), missing))
                .isInstanceOf(BusinessException.class);
    }
}

package com.stove.review.api.listener;

import static com.stove.review.ReviewFixtures.message;
import static com.stove.review.ReviewFixtures.event;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.ProjectReviewRequestedEvent;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class ReviewSubmissionMapperTest {
    @Test
    void rejectsUnrelatedHeaderMetadataAndMissingSubmissionBeforeIntake() {
        ProjectReviewRequestedEvent event = event(1, 1);
        for (EventMessage message : new EventMessage[]{null,
                new EventMessage("other", event.eventType(), event.partitionKey(), ""),
                new EventMessage(event.eventId(), "other", event.partitionKey(), ""),
                new EventMessage(event.eventId(), event.eventType(), "other", "")})
            assertThatThrownBy(() -> ReviewSubmissionMapper.from(message, event))
                    .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReviewSubmissionMapper.from(message(event), null))
                .isInstanceOf(BusinessException.class);
        ProjectReviewRequestedEvent missing = new ProjectReviewRequestedEvent(event.eventId(), event.occurredAt(), 1L, null);
        assertThatThrownBy(() -> ReviewSubmissionMapper.from(message(event), missing))
                .isInstanceOf(BusinessException.class);
    }
}

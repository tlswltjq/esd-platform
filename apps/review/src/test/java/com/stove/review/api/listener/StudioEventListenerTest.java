package com.stove.review.api.listener;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;
import com.stove.review.ReviewFixtures;
import com.stove.review.core.service.ReceiveProjectReview;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class StudioEventListenerTest {
    private final ReceiveProjectReview receive = mock(ReceiveProjectReview.class);
    private final StudioEventListener listener =
            new StudioEventListener(ReviewFixtures.JSON, receive);

    @Test
    void forwardsTheCurrentProjectRequest() {
        ProjectReviewRequestedEvent e = ReviewFixtures.event(1, 1);
        ConsumerRecord<String, String> record = record(e.eventId(), e.eventType(), ReviewFixtures.json(e));
        listener.receive(record);
        verify(receive).execute(ReviewSubmissionMapper.from(ReviewFixtures.envelope(e), e));
    }

    @Test
    void ignoresLegacyAndReleaseEvents() {
        listener.receive(record("old", "GameRegistered", "{}"));
        listener.receive(record("other", "ProjectReleaseRequested", "{}"));
        verifyNoInteractions(receive);
    }

    @Test
    void missingContractHeadersCannotBeAcknowledgedAsAValidRequest() {
        assertThatThrownBy(
                        () ->
                                listener.receive(
                                        new ConsumerRecord<>(Topics.STUDIO, 0, 0, "GAME-1", "{}")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(receive);
    }

    private ConsumerRecord<String, String> record(String id, String type, String payload) {
        ConsumerRecord<String, String> r = new ConsumerRecord<String, String>(Topics.STUDIO, 0, 0, "GAME-1", payload);
        r.headers().add(EventHeaders.EVENT_ID, id.getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        return r;
    }
}

package com.stove.studio.api.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseCompletedEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseRejectedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewApprovedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewRejectedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.studio.core.domain.release.ProjectReleaseResult;
import com.stove.studio.core.domain.review.ProjectReviewResult;
import com.stove.studio.core.service.release.RecordProjectReleaseResult;
import com.stove.studio.core.service.review.RecordProjectReviewResult;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProjectResultMapperTest {
    static Stream<DomainEvent> results() {
        return Stream.of(ProjectReviewApprovedEvent.of(1L, 2L, 3, "game-2", "reviewer"),
                ProjectReviewRejectedEvent.of(1L, 2L, 3, "game-2", "Fix the build", "reviewer"),
                ProjectReleaseCompletedEvent.of(1L, 2L, 3, "game-2", "original-request"),
                ProjectReleaseRejectedEvent.of(1L, 2L, 3, "game-2", "Stale version", "original-request"));
    }

    private Object convert(EventEnvelope envelope, DomainEvent event) {
        return switch (event) {
            case ProjectReviewApprovedEvent approved -> ProjectResultMapper.from(envelope, approved);
            case ProjectReviewRejectedEvent rejected -> ProjectResultMapper.from(envelope, rejected);
            case ProjectReleaseCompletedEvent completed -> ProjectResultMapper.from(envelope, completed);
            case ProjectReleaseRejectedEvent rejected -> ProjectResultMapper.from(envelope, rejected);
            default -> throw new IllegalArgumentException();
        };
    }

    @ParameterizedTest @MethodSource("results")
    void listenerDeliversInternalInputForEveryResult(DomainEvent event) throws Exception {
        RecordProjectReviewResult review = mock(RecordProjectReviewResult.class);
        RecordProjectReleaseResult release = mock(RecordProjectReleaseResult.class);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ProjectResultListener listener = new ProjectResultListener(json, review, release);
        ConsumerRecord<String, String> record = new ConsumerRecord<String, String>(event.topic(), 0, 0, event.partitionKey(), json.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        Object input = convert(new EventEnvelope(event.eventId(), event.eventType(), event.partitionKey(), ""), event);
        listener.receive(record);
        if (input instanceof ProjectReviewResult result) {
            verify(review).execute(result);
            verifyNoInteractions(release);
        } else {
            verify(release).execute((ProjectReleaseResult) input);
            verifyNoInteractions(review);
        }
        record.headers().remove(EventHeaders.EVENT_ID);
        record.headers().add(EventHeaders.EVENT_ID, "changed-header".getBytes(StandardCharsets.UTF_8));
        clearInvocations(review, release);
        assertThatThrownBy(() -> listener.receive(record)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(review, release);
    }

    @ParameterizedTest @MethodSource("results")
    void requiresMatchingIdTypeAndProductKey(DomainEvent event) {
        for (EventEnvelope envelope : new EventEnvelope[]{null,
                new EventEnvelope("other", event.eventType(), event.partitionKey(), ""),
                new EventEnvelope(event.eventId(), "other", event.partitionKey(), ""),
                new EventEnvelope(event.eventId(), event.eventType(), "other", "")})
            assertThatThrownBy(() -> convert(envelope, event)).isInstanceOf(BusinessException.class);
    }

    @Test
    void incompleteResultsCannotBecomeInternalInput() {
        ProjectReviewApprovedEvent event = ProjectReviewApprovedEvent.of(1L, 2L, 3, "game-2", "reviewer");
        EventEnvelope envelope = new EventEnvelope(event.eventId(), event.eventType(), event.partitionKey(), "");
        assertThatThrownBy(() -> ProjectResultMapper.from(envelope,
                new ProjectReviewApprovedEvent(event.eventId(), null, 1L, 2L, 3, "game-2", "reviewer")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ProjectResultMapper.from(envelope,
                new ProjectReviewApprovedEvent(event.eventId(), event.occurredAt(), 1L, 2L, 3, "game-2", "")))
                .isInstanceOf(BusinessException.class);
        ProjectReleaseRejectedEvent rejected = ProjectReleaseRejectedEvent.of(1L, 2L, 3, "game-2", "", "request");
        assertThatThrownBy(() -> convert(new EventEnvelope(rejected.eventId(), rejected.eventType(), rejected.partitionKey(), ""), rejected))
                .isInstanceOf(BusinessException.class);
    }
}

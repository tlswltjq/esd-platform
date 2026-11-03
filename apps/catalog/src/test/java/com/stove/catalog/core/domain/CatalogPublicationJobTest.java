package com.stove.catalog.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.catalog.CatalogFixtures;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CatalogPublicationJobTest {
    @Test
    void acceptanceIsDurableWorkAndRetryDoesNotReplaceItsOriginalInputOrTrace() {
        ProjectReleaseRequestedEvent event = CatalogFixtures.event(120, 2);
        ReleaseSubmission input = ReleaseSubmissionMapper.from(CatalogFixtures.envelope(event), event);
        String json = CatalogFixtures.json(event.snapshot());
        String trace = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";
        CatalogPublicationJob job = CatalogPublicationJob.accept(input, json, trace);
        job.requireSame(input, event.snapshot());
        Instant failedAt = Instant.parse("2026-11-03T00:00:00Z");
        job.failed("database unavailable", failedAt);
        assertThat(job.getState()).isEqualTo("PENDING");
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isEqualTo(failedAt.plusSeconds(5));
        assertThat(job.due(failedAt)).isFalse();
        assertThat(job.due(failedAt.plusSeconds(5))).isTrue();
        assertThat(job.getSourceEventId()).isEqualTo(event.eventId());
        assertThat(job.getSnapshot()).isEqualTo(json);
        assertThat(job.getTraceParent()).isEqualTo(trace);
        job.complete();
        job.failed("late failure", failedAt.plusSeconds(10));
        assertThat(job.getState()).isEqualTo("COMPLETED");
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getLastError()).isNull();
        assertThat(job.due(failedAt.plusSeconds(100))).isFalse();
    }

    @Test
    void sameRequestCannotAcquireDifferentInputEvenWithTheSameEventId() {
        ProjectReleaseRequestedEvent event = CatalogFixtures.event(121, 2);
        ReleaseSubmission input = ReleaseSubmissionMapper.from(CatalogFixtures.envelope(event), event);
        CatalogPublicationJob job = CatalogPublicationJob.accept(input, CatalogFixtures.json(event.snapshot()), null);
        assertThatThrownBy(() -> job.requireSame(input, CatalogFixtures.event(121, 3).snapshot()))
                .isInstanceOf(BusinessException.class);
    }
}

package com.stove.catalog.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.catalog.CatalogFixtures;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalRequest;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReleaseWithdrawalTest {
    @Test
    void aPublicationThatWonTheRaceCannotBeRolledBackByWithdrawal() {
        ProjectReleaseRequestedEvent event = CatalogFixtures.event(110, 2);
        ReleaseSubmission submission = ReleaseSubmissionMapper.from(CatalogFixtures.envelope(event), event);
        Instant first = Instant.parse("2026-11-02T03:00:00Z");
        CatalogRelease release = CatalogRelease.decided(submission, CatalogFixtures.json(event.snapshot()), false, first);
        ProjectWithdrawalResult result = command(event, 1).resolve(release, event.snapshot());
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.finalizedAt()).isEqualTo(first);
        assertThat(result.requestEventId()).isEqualTo(event.eventId());
        assertThat(release.getStatus()).isEqualTo("COMPLETED");
        assertThat(release.getWithdrawnBy()).isNull();
    }

    @Test
    void withdrawnReleasePreservesTheApprovalReferenceAndOriginalSnapshot() {
        ProjectReleaseRequestedEvent event = CatalogFixtures.event(111, 2);
        ReleaseSubmission submission = ReleaseSubmissionMapper.from(CatalogFixtures.envelope(event), event);
        String snapshot = CatalogFixtures.json(event.snapshot());
        Instant first = Instant.parse("2026-11-02T03:00:00Z");
        CatalogRelease release = CatalogRelease.withdrawn(submission, snapshot, first, "creator", null);
        ProjectWithdrawalResult result = command(event, 2).resolve(release, event.snapshot());
        assertThat(result.status()).isEqualTo("WITHDRAWN");
        assertThat(result.actor()).isEqualTo("creator");
        assertThat(result.feedback()).isNull();
        assertThat(release.getReviewRequestId()).isEqualTo(event.reviewRequestId());
        assertThat(release.getSnapshot()).isEqualTo(snapshot);
        assertThatThrownBy(() -> CatalogRelease.withdrawn(submission, snapshot, first, "another", null))
                .isInstanceOf(BusinessException.class);
    }

    private ReleaseWithdrawal command(ProjectReleaseRequestedEvent event, long id) {
        return ReleaseWithdrawal.receive(new ReleaseWithdrawalInput("withdrawal-" + id,
                "ProjectReleaseWithdrawalRequested", Instant.now(),
                new ProjectWithdrawalRequest(id, event.releaseRequestId(), event.snapshot().projectId(),
                        event.snapshot().contentVersion(), event.snapshot().productCode(), "creator", null)));
    }
}

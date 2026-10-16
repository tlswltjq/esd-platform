package com.stove.studio.core.domain.review;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProjectReviewRequestTest {
    @Test
    void externalApprovalOnlySatisfiesTheReviewedMetadataVersion() {
        ProjectReviewRequest r = ProjectReviewRequest.create(1L, 2, 3L, "snapshot");
        assertThat(r.approvedFor(2)).isFalse();
        r.recordResult(true, null, "external-reviewer");
        assertThat(r.approvedFor(2)).isTrue();
        assertThat(r.approvedFor(3)).isFalse();
    }

    @Test
    void contradictoryLateResultsDoNotOverwriteTheRecordedRejection() {
        ProjectReviewRequest r = ProjectReviewRequest.create(1L, 2, 3L, "snapshot");
        r.recordResult(false, "Fix details", "external-reviewer");
        r.recordResult(true, null, "late-result");
        assertThat(r.getStatus()).isEqualTo("REJECTED");
        assertThat(r.getFeedback()).isEqualTo("Fix details");
    }
}

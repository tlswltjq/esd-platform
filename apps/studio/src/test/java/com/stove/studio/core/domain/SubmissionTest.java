package com.stove.studio.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubmissionTest {

    private Submission submitted() {
        return Submission.create(1L, 2L, 1, 10L, 20L, 30L, 40L);
    }

    @Test
    @DisplayName("수정 요청된 제출은 뒤늦은 승인으로 출시 대기 상태가 되지 않는다")
    void changesRequestedIsTerminalForTheSnapshot() {
        Submission submission = submitted();

        submission.changesRequested();
        submission.readyForRelease();

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.CHANGES_REQUESTED);
    }

    @Test
    @DisplayName("제출은 모든 심의 통과 뒤에만 출시되고 등급 증빙을 보존한다")
    void readySubmissionCanBeReleasedWithRatingEvidence() {
        Submission submission = submitted();
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");

        submission.applyRating("15", "SELF-1", "ESD", issuedAt, "KR",
                "SELF_CLASSIFICATION", "KR-2026-01", null, null);
        submission.readyForRelease();
        submission.released();

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.RELEASED);
        assertThat(submission.getRatingCode()).isEqualTo("15");
        assertThat(submission.getRatingCertificationNumber()).isEqualTo("SELF-1");
        assertThat(submission.getRatingIssuer()).isEqualTo("ESD");
        assertThat(submission.getRatingIssuedAt()).isEqualTo(issuedAt);
        assertThat(submission.getRatingCountry()).isEqualTo("KR");
        assertThat(submission.getRatingPath()).isEqualTo("SELF_CLASSIFICATION");
        assertThat(submission.getRatingPolicyVersion()).isEqualTo("KR-2026-01");
    }

    @Test
    @DisplayName("심의 대기 중인 제출은 바로 출시할 수 없다")
    void submittedCannotBeReleasedDirectly() {
        Submission submission = submitted();

        submission.released();

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
    }
}

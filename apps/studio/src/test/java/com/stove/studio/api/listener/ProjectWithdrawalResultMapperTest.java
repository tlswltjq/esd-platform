package com.stove.studio.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.ProjectReleaseWithdrawalResolvedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewWithdrawalResolvedEvent;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.web.error.BusinessException;
import com.stove.studio.core.domain.project.ProjectRequestKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProjectWithdrawalResultMapperTest {
    @Test
    void reviewAndReleaseResultsKeepTheirOwnTerminalVocabularyAndEnvelope() {
        ProjectWithdrawalResult result = new ProjectWithdrawalResult(1L, 2L, 3L, 4, "GAME", "WITHDRAWN", "owner", null,
                Instant.parse("2026-11-03T00:00:00Z"), "original-event");
        ProjectReviewWithdrawalResolvedEvent review = ProjectReviewWithdrawalResolvedEvent.of(result);
        ProjectReleaseWithdrawalResolvedEvent release = ProjectReleaseWithdrawalResolvedEvent.of(result);
        assertThat(ProjectWithdrawalResultMapper.from(envelope(review.eventId(), review.eventType(), "GAME"), review).kind())
                .isEqualTo(ProjectRequestKind.WITHDRAW_REVIEW);
        assertThat(ProjectWithdrawalResultMapper.from(envelope(release.eventId(), release.eventType(), "GAME"), release).kind())
                .isEqualTo(ProjectRequestKind.WITHDRAW_RELEASE);
        assertThatThrownBy(() -> ProjectWithdrawalResultMapper.from(envelope(review.eventId(), review.eventType(), "OTHER"), review))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ProjectWithdrawalResultMapper.from(envelope("changed", release.eventType(), "GAME"), release))
                .isInstanceOf(BusinessException.class);
        ProjectReleaseWithdrawalResolvedEvent incomplete = new ProjectReleaseWithdrawalResolvedEvent(release.eventId(), null, result);
        assertThatThrownBy(() -> ProjectWithdrawalResultMapper.from(envelope(incomplete.eventId(), incomplete.eventType(), "GAME"), incomplete))
                .isInstanceOf(BusinessException.class);
        ProjectReviewWithdrawalResolvedEvent wrongStatus = ProjectReviewWithdrawalResolvedEvent.of(new ProjectWithdrawalResult(
                1L, 2L, 3L, 4, "GAME", "COMPLETED", "owner", null, result.finalizedAt(), "original-event"));
        assertThatThrownBy(() -> ProjectWithdrawalResultMapper.from(envelope(wrongStatus.eventId(), wrongStatus.eventType(), "GAME"), wrongStatus))
                .isInstanceOf(BusinessException.class);
    }

    private EventEnvelope envelope(String id, String type, String key) { return new EventEnvelope(id, type, key, ""); }
}

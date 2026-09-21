package com.stove.review.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.SubmissionCreatedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.review.core.domain.RatingBoardSubmission;
import com.stove.review.core.domain.ReviewCase;
import com.stove.review.core.domain.ReviewCaseRepository;
import com.stove.review.core.domain.ReviewCaseStatus;
import com.stove.review.core.domain.ReviewType;
import com.stove.review.core.domain.SubmissionSnapshotRepository;
import com.stove.review.core.port.RatingBoardClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SubmissionReviewServiceTest {

    private final SubmissionSnapshotRepository snapshots = mock(SubmissionSnapshotRepository.class);
    private final ReviewCaseRepository cases = mock(ReviewCaseRepository.class);
    private final ProcessedEventGuard guard = mock(ProcessedEventGuard.class);
    private final OutboxRecorder outbox = mock(OutboxRecorder.class);
    private final AuditLogService auditLog = mock(AuditLogService.class);
    private final RatingBoardClient ratingBoard = mock(RatingBoardClient.class);
    private final SubmissionReviewService service = new SubmissionReviewService(
            snapshots, cases, guard, outbox, auditLog, ratingBoard);

    @Test
    @DisplayName("전체·12·15세 자체등급 제출은 외부 기관에 접수하지 않는다")
    void receivesSelfClassificationWithoutExternalSubmission() {
        when(guard.firstDelivery(anyString(), anyString(), anyString())).thenReturn(true);

        service.receive("event-1", EventType.SUBMISSION_CREATED,
                submission("SELF_CLASSIFICATION", "15", "false", "false"));

        verify(ratingBoard, never()).submit(any(RatingBoardSubmission.class));
        ReviewCase ratingCase = capturedRatingCase();
        assertThat(ratingCase.getStatus()).isEqualTo(ReviewCaseStatus.REQUESTED);
        assertThat(ratingCase.getRatingPath()).isEqualTo("SELF_CLASSIFICATION");
        assertThat(ratingCase.getTargetRatingCode()).isEqualTo("15");
    }

    @Test
    @DisplayName("18세 제출은 불변 빌드와 설문 자료를 GRAC에 접수하고 접수번호를 저장한다")
    void submitsAdultRatingSnapshotToGrac() {
        when(guard.firstDelivery(anyString(), anyString(), anyString())).thenReturn(true);
        when(ratingBoard.submit(any(RatingBoardSubmission.class))).thenReturn("GRAC-2026-00001");

        service.receive("event-1", EventType.SUBMISSION_CREATED,
                submission("GRAC", "18", "true", "false"));

        ArgumentCaptor<RatingBoardSubmission> submission = ArgumentCaptor.forClass(RatingBoardSubmission.class);
        verify(ratingBoard).submit(submission.capture());
        assertThat(submission.getValue().submissionId()).isEqualTo(10L);
        assertThat(submission.getValue().buildId()).isEqualTo(20L);
        assertThat(submission.getValue().questionnaire()).contains("adultContent");

        ReviewCase ratingCase = capturedRatingCase();
        assertThat(ratingCase.getStatus()).isEqualTo(ReviewCaseStatus.EXTERNAL_SUBMITTED);
        assertThat(ratingCase.getExternalSubmissionId()).isEqualTo("GRAC-2026-00001");
    }

    private ReviewCase capturedRatingCase() {
        ArgumentCaptor<ReviewCase> reviewCases = ArgumentCaptor.forClass(ReviewCase.class);
        verify(cases, times(3)).save(reviewCases.capture());
        List<ReviewCase> values = reviewCases.getAllValues();
        return values.stream()
                .filter(value -> value.getReviewType() == ReviewType.RATING)
                .findFirst().orElseThrow();
    }

    private SubmissionCreatedEvent submission(String path, String targetRatingCode,
                                              String adultContent, String cashGambling) {
        String questionnaire = "{\"adultContent\":" + adultContent
                + ",\"cashGambling\":" + cashGambling + "}";
        return SubmissionCreatedEvent.of(10L, 1L, "GAME-001", 1001L,
                1L, 2L, 3L, 20L, "게임 A", "설명", 30_000L, "KRW",
                path, "KR-2026-01", "KR", targetRatingCode, questionnaire, "1.0.0");
    }
}

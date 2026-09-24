package com.stove.review.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.SubmissionCreatedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.review.core.domain.ReviewCase;
import com.stove.review.core.domain.ReviewCaseRepository;
import com.stove.review.core.domain.ReviewCaseStatus;
import com.stove.review.core.domain.ReviewType;
import com.stove.review.core.domain.SubmissionSnapshot;
import com.stove.review.core.domain.SubmissionSnapshotRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubmissionReviewServiceTest {

    private final SubmissionSnapshotRepository snapshotRepository = mock(SubmissionSnapshotRepository.class);
    private final ReviewCaseRepository caseRepository = mock(ReviewCaseRepository.class);
    private final ProcessedEventGuard processedEventGuard = mock(ProcessedEventGuard.class);
    private final OutboxRecorder outboxRecorder = mock(OutboxRecorder.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final SubmissionReviewService service = new SubmissionReviewService(
            snapshotRepository, caseRepository, processedEventGuard, outboxRecorder, auditLogService);

    @Test
    @DisplayName("자체등급은 정책 결정 코드만 받으며 플랫폼 인증 증빙을 발급한다")
    void selfClassificationIssuesEvidence() {
        ReviewCase reviewCase = prepare("SELF_CLASSIFICATION", "12");

        service.approve(1L, "reviewer", new ReviewCase.RatingDecision("12", null, null, null, null));

        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.APPROVED);
        assertThat(reviewCase.getCertificationNumber()).isEqualTo("ESD-SELF-10");
        assertThat(reviewCase.getCountry()).isEqualTo("KR");
    }

    @Test
    @DisplayName("GRAC 경로는 외부 접수 증빙 없이는 승인할 수 없다")
    void gracApprovalRequiresExternalSubmission() {
        ReviewCase reviewCase = prepare("GRAC", "18");

        assertThatThrownBy(() -> service.approve(1L, "reviewer", rating("18")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.REQUESTED);
    }

    @Test
    @DisplayName("GRAC 외부 접수 뒤 정책 등급과 같은 인증 결과로 승인한다")
    void approvesGracAfterExternalSubmission() {
        ReviewCase reviewCase = prepare("GRAC", "18");
        Instant submittedAt = Instant.now().minusSeconds(60);

        service.submitExternal(1L, "reviewer", "GRAC-1", submittedAt,
                "https://evidence.example/GRAC-1");
        service.approve(1L, "reviewer", rating("18"));

        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.APPROVED);
        assertThat(reviewCase.getExternalApplicationNumber()).isEqualTo("GRAC-1");
    }

    @Test
    @DisplayName("정책이 산출한 연령 등급과 다른 코드로 승인할 수 없다")
    void rejectsRatingDifferentFromPolicyDecision() {
        ReviewCase reviewCase = prepare("SELF_CLASSIFICATION", "12");

        assertThatThrownBy(() -> service.approve(1L, "reviewer",
                new ReviewCase.RatingDecision("ALL", null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.REQUESTED);
    }

    @Test
    @DisplayName("자체등급 경로에는 GRAC 외부 접수를 기록할 수 없다")
    void selfClassificationRejectsExternalSubmission() {
        prepare("SELF_CLASSIFICATION", "ALL");

        assertThatThrownBy(() -> service.submitExternal(1L, "reviewer", "GRAC-1",
                Instant.now(), "https://evidence.example/GRAC-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private ReviewCase prepare(String path, String recommendedRatingCode) {
        ReviewCase reviewCase = ReviewCase.ratingRequested(10L, path, recommendedRatingCode);
        SubmissionSnapshot snapshot = SubmissionSnapshot.from(SubmissionCreatedEvent.of(
                10L, 1L, "GAME-001", 1001L, 1L, 1L, 1L, 2L,
                "게임 A", "설명", 30_000L, "KRW", path, "KR-2026-01",
                "KR", recommendedRatingCode, "{}", "1.0.0"));
        when(caseRepository.findById(1L)).thenReturn(Optional.of(reviewCase));
        when(snapshotRepository.findById(10L)).thenReturn(Optional.of(snapshot));
        return reviewCase;
    }

    private ReviewCase.RatingDecision rating(String code) {
        return new ReviewCase.RatingDecision(code, "CERT-1", "GRAC",
                Instant.parse("2026-09-21T00:00:00Z"), "KR");
    }
}

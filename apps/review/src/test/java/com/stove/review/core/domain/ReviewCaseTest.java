package com.stove.review.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReviewCaseTest {

    @Test
    @DisplayName("자체등급 승인은 정책 등급에 내부 인증 증빙을 발급한다")
    void selfClassificationIssuesInternalEvidence() {
        ReviewCase reviewCase = ReviewCase.ratingRequested(7L, "SELF_CLASSIFICATION", "15");

        reviewCase.approveSelfClassification("reviewer", "15", "KR");

        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.APPROVED);
        assertThat(reviewCase.getCertificationNumber()).isEqualTo("ESD-SELF-7");
        assertThat(reviewCase.getIssuer()).isEqualTo("ESD SELF CLASSIFICATION");
        assertThat(reviewCase.getCountry()).isEqualTo("KR");
    }

    @Test
    @DisplayName("18세 등급은 자체등급 경로에서 승인할 수 없다")
    void selfClassificationRejectsAdultRating() {
        ReviewCase reviewCase = ReviewCase.ratingRequested(1L, "SELF_CLASSIFICATION", "15");

        assertThatThrownBy(() -> reviewCase.approveSelfClassification("reviewer", "18", "KR"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("GRAC 등급 심사는 외부 접수 정보와 증빙을 보존한다")
    void externalSubmissionRecordsEvidence() {
        ReviewCase reviewCase = ReviewCase.ratingRequested(1L, "GRAC", "18");
        Instant submittedAt = Instant.parse("2026-01-01T00:00:00Z");

        reviewCase.submitExternal("GRAC-2026-1", submittedAt, "https://evidence.example/GRAC-2026-1");

        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.EXTERNAL_SUBMITTED);
        assertThat(reviewCase.getExternalApplicationNumber()).isEqualTo("GRAC-2026-1");
        assertThat(reviewCase.getExternalSubmittedAt()).isEqualTo(submittedAt);
        assertThat(reviewCase.hasExternalSubmission()).isTrue();
    }

    @Test
    @DisplayName("GRAC 외부 접수는 유효한 접수번호·시각·HTTPS 증빙을 요구한다")
    void externalSubmissionRequiresValidEvidence() {
        ReviewCase reviewCase = ReviewCase.ratingRequested(1L, "GRAC", "18");

        assertThatThrownBy(() -> reviewCase.submitExternal("", Instant.now(), "http://evidence.example/1"))
                .isInstanceOf(BusinessException.class);
        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.REQUESTED);
    }

    @Test
    @DisplayName("GRAC 심의는 외부 접수와 완전한 인증 결과가 있어야 승인된다")
    void externalRatingRequiresCompleteEvidence() {
        ReviewCase reviewCase = ReviewCase.ratingRequested(1L, "GRAC", "18");

        assertThatThrownBy(() -> reviewCase.approveExternalRating("reviewer", rating("18")))
                .isInstanceOf(BusinessException.class);

        reviewCase.submitExternal("GRAC-2026-1", Instant.now(), "https://evidence.example/1");
        reviewCase.approveExternalRating("reviewer", rating("18"));

        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.APPROVED);
        assertThat(reviewCase.getCertificationNumber()).isEqualTo("GRAC-CERT-1");
    }

    @Test
    @DisplayName("한 번 결정된 심의는 다시 승인하거나 수정 요청할 수 없다")
    void decisionIsTerminal() {
        ReviewCase reviewCase = ReviewCase.requested(1L, ReviewType.BUILD_QA);
        reviewCase.approve("reviewer");

        assertThatThrownBy(() -> reviewCase.requestChanges("reviewer", "BUILD", "다시 업로드"))
                .isInstanceOf(BusinessException.class);
        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.APPROVED);
    }

    @Test
    @DisplayName("차단된 심사는 이의 제기로 새 SLA와 심사 회차를 열 수 있다")
    void appealReopensBlockedReview() {
        ReviewCase reviewCase = ReviewCase.requested(1L, ReviewType.LEGAL);
        Instant firstDueAt = reviewCase.getDueAt();

        reviewCase.block("reviewer", "LEGAL_DOCUMENT", "계약서 확인 필요",
                "https://evidence.example/legal/1");
        reviewCase.appeal("계약서를 보완했습니다.");

        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.REQUESTED);
        assertThat(reviewCase.getReviewRound()).isEqualTo(2);
        assertThat(reviewCase.getAppealReason()).isEqualTo("계약서를 보완했습니다.");
        assertThat(reviewCase.getDueAt()).isAfterOrEqualTo(firstDueAt);
    }

    @Test
    @DisplayName("심사 체크리스트와 내부 메모는 외부 피드백과 분리해 보존한다")
    void checklistKeepsInternalMemoSeparate() {
        ReviewCase reviewCase = ReviewCase.requested(1L, ReviewType.SDK_COMPLIANCE);

        reviewCase.assign("reviewer-1");
        reviewCase.updateChecklist("{\"sdkLogin\":true}", "내부에서만 볼 메모");

        assertThat(reviewCase.getAssignedTo()).isEqualTo("reviewer-1");
        assertThat(reviewCase.getChecklistJson()).contains("sdkLogin");
        assertThat(reviewCase.getInternalMemo()).isEqualTo("내부에서만 볼 메모");
        assertThat(reviewCase.getExternalFeedback()).isNull();
    }

    private ReviewCase.RatingDecision rating(String code) {
        return new ReviewCase.RatingDecision(code, "GRAC-CERT-1", "GRAC",
                Instant.parse("2026-01-01T00:00:00Z"), "KR");
    }
}

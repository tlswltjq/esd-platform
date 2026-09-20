package com.stove.review.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReviewCaseTest {

    @Test
    @DisplayName("등급 심의는 코드와 인증 증빙이 모두 있어야 승인된다")
    void ratingRequiresCompleteEvidence() {
        ReviewCase reviewCase = ReviewCase.requested(1L, ReviewType.RATING);

        assertThatThrownBy(() -> reviewCase.approve("reviewer", new ReviewCase.RatingDecision(
                "15", "", "ESD", Instant.now(), "KR")))
                .isInstanceOf(BusinessException.class);
        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.REQUESTED);
    }

    @Test
    @DisplayName("등급 승인 결과는 인증 증빙과 함께 기록된다")
    void ratingApprovalRecordsEvidence() {
        ReviewCase reviewCase = ReviewCase.requested(1L, ReviewType.RATING);
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");

        reviewCase.approve("reviewer", new ReviewCase.RatingDecision(
                "15", "SELF-1", "ESD", issuedAt, "KR"));

        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.APPROVED);
        assertThat(reviewCase.getDecidedBy()).isEqualTo("reviewer");
        assertThat(reviewCase.getRatingCode()).isEqualTo("15");
        assertThat(reviewCase.getCertificationNumber()).isEqualTo("SELF-1");
        assertThat(reviewCase.getIssuedAt()).isEqualTo(issuedAt);
    }

    @Test
    @DisplayName("한 번 결정된 심의는 다시 승인하거나 수정 요청할 수 없다")
    void decisionIsTerminal() {
        ReviewCase reviewCase = ReviewCase.requested(1L, ReviewType.BUILD_QA);
        reviewCase.approve("reviewer", null);

        assertThatThrownBy(() -> reviewCase.requestChanges("reviewer", "BUILD", "다시 업로드"))
                .isInstanceOf(BusinessException.class);
        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.APPROVED);
    }
}

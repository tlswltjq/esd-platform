package com.stove.review.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReviewCaseTest {

    @Test
    @DisplayName("GRAC 심의는 외부 접수번호와 완전한 인증 증빙이 있어야 승인된다")
    void externalRatingRequiresCompleteEvidence() {
        ReviewCase reviewCase = ReviewCase.externalSubmitted(1L, "18", "GRAC-2026-1");

        assertThatThrownBy(() -> reviewCase.approveExternalRating("reviewer", new ReviewCase.RatingDecision(
                "18", "", "GRAC", Instant.now(), "KR")))
                .isInstanceOf(BusinessException.class);
        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.EXTERNAL_SUBMITTED);
    }

    @Test
    @DisplayName("GRAC 승인 결과는 외부 인증 증빙과 함께 기록된다")
    void externalRatingApprovalRecordsEvidence() {
        ReviewCase reviewCase = ReviewCase.externalSubmitted(1L, "18", "GRAC-2026-1");
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");

        reviewCase.approveExternalRating("reviewer", new ReviewCase.RatingDecision(
                "18", "GRAC-CERT-1", "GRAC", issuedAt, "KR"));

        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.APPROVED);
        assertThat(reviewCase.getDecidedBy()).isEqualTo("reviewer");
        assertThat(reviewCase.getRatingCode()).isEqualTo("18");
        assertThat(reviewCase.getCertificationNumber()).isEqualTo("GRAC-CERT-1");
        assertThat(reviewCase.getIssuedAt()).isEqualTo(issuedAt);
    }

    @Test
    @DisplayName("자체등급 승인은 제출한 전체·12·15세 등급에 내부 인증 증빙을 발급한다")
    void selfClassificationIssuesInternalEvidence() {
        ReviewCase reviewCase = ReviewCase.selfClassification(7L, "15");

        reviewCase.approveSelfClassification("reviewer", "15", "KR");

        assertThat(reviewCase.getStatus()).isEqualTo(ReviewCaseStatus.APPROVED);
        assertThat(reviewCase.getCertificationNumber()).isEqualTo("ESD-SELF-7");
        assertThat(reviewCase.getIssuer()).isEqualTo("ESD SELF CLASSIFICATION");
        assertThat(reviewCase.getCountry()).isEqualTo("KR");
    }

    @Test
    @DisplayName("18세 등급은 자체등급 경로에서 승인할 수 없다")
    void selfClassificationRejectsAdultRating() {
        ReviewCase reviewCase = ReviewCase.selfClassification(1L, "15");

        assertThatThrownBy(() -> reviewCase.approveSelfClassification("reviewer", "18", "KR"))
                .isInstanceOf(BusinessException.class);
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
}

package com.stove.review.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.review.ReviewFixtures;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProjectReviewTest {
    @Test
    void creatorCanWithdrawBeforeDecisionAndTheFirstWithdrawalIsPreserved() {
        ProjectReview review = ReviewFixtures.review(1002);
        Instant first = Instant.parse("2026-11-02T03:00:00Z");
        assertThat(review.withdraw("creator", null, first)).isTrue();
        assertThat(review.withdraw("creator", "later", first.plusSeconds(1))).isFalse();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.WITHDRAWN);
        assertThat(review.getWithdrawnAt()).isEqualTo(first);
        assertThat(review.getWithdrawalReason()).isNull();
        assertThatThrownBy(() -> review.decide(ReviewStatus.APPROVED, "reviewer", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void withdrawalCannotReplaceADecisionOrBeRequestedByAnotherCreator() {
        ProjectReview review = ReviewFixtures.review(1003);
        assertThatThrownBy(() -> review.withdraw("another", null, Instant.now()))
                .isInstanceOf(BusinessException.class);
        review.decide(ReviewStatus.REJECTED, "reviewer", "Fix description");
        Instant decidedAt = review.getDecidedAt();
        assertThat(review.withdraw("creator", "changed plans", Instant.now())).isFalse();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(review.getDecidedAt()).isEqualTo(decidedAt);
        assertThat(review.getFeedback()).isEqualTo("Fix description");
        assertThat(review.getWithdrawnAt()).isNull();
    }

    @Test
    void withdrawalCannotBeSubmittedAsAHumanApprovalOrRejection() {
        ProjectReview review = ReviewFixtures.review(1001);
        assertThatThrownBy(() -> review.decide(ReviewStatus.WITHDRAWN, "reviewer", null))
                .isInstanceOf(BusinessException.class);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.REQUESTED);
    }

    @Test
    void identicalDecisionRetryKeepsOriginalActorAndTime() {
        ProjectReview r = ReviewFixtures.review(1);
        assertThat(r.decide(ReviewStatus.APPROVED, "first", null)).isTrue();
        Instant time = r.getDecidedAt();
        assertThat(r.decide(ReviewStatus.APPROVED, "second", null)).isFalse();
        assertThat(r.getActor()).isEqualTo("first");
        assertThat(r.getDecidedAt()).isEqualTo(time);
        assertThatThrownBy(() -> r.decide(ReviewStatus.REJECTED, "second", "Change"))
                .isInstanceOf(BusinessException.class);
        assertThat(r.getStatus()).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void rejectionCannotBeReopenedOrHaveItsReasonChanged() {
        ProjectReview r = ReviewFixtures.review(1);
        r.decide(ReviewStatus.REJECTED, "reviewer", "Fix description");
        assertThat(r.decide(ReviewStatus.REJECTED, "retry", "Fix description")).isFalse();
        assertThatThrownBy(() -> r.decide(ReviewStatus.REJECTED, "retry", "Different"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> r.decide(ReviewStatus.REQUESTED, "retry", null))
                .isInstanceOf(BusinessException.class);
        assertThat(r.getFeedback()).isEqualTo("Fix description");
    }

    @Test
    void invalidDecisionDoesNotMutateTheRequest() {
        ProjectReview r = ReviewFixtures.review(1);
        for (String feedback : new String[] {"", " ", "x".repeat(2001)})
            assertThatThrownBy(() -> r.decide(ReviewStatus.REJECTED, "reviewer", feedback))
                    .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> r.decide(ReviewStatus.APPROVED, "", null))
                .isInstanceOf(BusinessException.class);
        assertThat(r.getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        assertThat(r.getDecidedAt()).isNull();
    }
}

package com.stove.review.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.contract.payload.ProjectWithdrawalRequest;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.web.error.BusinessException;
import com.stove.review.ReviewFixtures;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReviewWithdrawalTest {
    @Test
    void aDecisionThatWonTheRaceIsReturnedWithItsOriginalActorAndTime() {
        ProjectReview review = ReviewFixtures.review(110);
        review.decide(ReviewStatus.APPROVED, "original-reviewer", null);
        Instant decidedAt = review.getDecidedAt();
        ReviewWithdrawal withdrawal = command(10, 110, "plans changed");
        ProjectWithdrawalResult result = withdrawal.resolve(review);
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.actor()).isEqualTo("original-reviewer");
        assertThat(result.finalizedAt()).isEqualTo(decidedAt);
        assertThat(result.requestEventId()).isEqualTo(review.getRequestEventId());
        assertThat(withdrawal.isResolved()).isTrue();
        assertThat(review.getWithdrawnAt()).isNull();
    }

    @Test
    void creatorWithdrawalCanHaveNoReasonAndDoesNotBecomeARejection() {
        ProjectReview review = ReviewFixtures.review(111);
        ProjectWithdrawalResult result = command(11, 111, null).resolve(review);
        assertThat(result.status()).isEqualTo("WITHDRAWN");
        assertThat(result.actor()).isEqualTo("creator");
        assertThat(result.feedback()).isNull();
        assertThat(result.finalizedAt()).isEqualTo(review.getWithdrawnAt());
        assertThat(review.getFeedback()).isNull();
        assertThat(review.getDecidedAt()).isNull();
    }

    @Test
    void anotherTargetAndConflictingInstructionCannotReplaceTheOriginalCommand() {
        ReviewWithdrawal withdrawal = command(12, 112, "original");
        assertThatThrownBy(() -> withdrawal.resolve(ReviewFixtures.review(113)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> withdrawal.requireSame(
                new ProjectWithdrawalRequest(12L, 112L, 112L, 1, "GAME-112", "creator", "changed")))
                .isInstanceOf(BusinessException.class);
        assertThat(withdrawal.isResolved()).isFalse();
    }

    private ReviewWithdrawal command(long id, long target, String reason) {
        return ReviewWithdrawal.receive(new ReviewWithdrawalInput("event-" + id,
                "ProjectReviewWithdrawalRequested", Instant.now(),
                new ProjectWithdrawalRequest(id, target, target, 1, "GAME-" + target, "creator", reason)));
    }
}

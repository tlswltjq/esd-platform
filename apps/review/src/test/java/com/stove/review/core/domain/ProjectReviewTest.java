package com.stove.review.core.domain;

import static org.assertj.core.api.Assertions.*;

import com.stove.common.web.error.BusinessException;
import com.stove.review.ReviewFixtures;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProjectReviewTest {
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

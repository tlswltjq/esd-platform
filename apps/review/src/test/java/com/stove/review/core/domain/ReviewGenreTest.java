package com.stove.review.core.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.review.ReviewFixtures;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReviewGenreTest {
    @Test void anOtherwiseValidSubmissionWithoutGenresIsRejected() throws Exception {
        ObjectNode tree = ReviewFixtures.JSON.valueToTree(ReviewFixtures.snapshot(1, "s3://stove-builds/file.zip", 10));
        tree.remove("genres");
        ProjectSnapshot snapshot = ReviewFixtures.JSON.treeToValue(tree, ProjectSnapshot.class);
        assertThatThrownBy(() -> new ReviewSubmission("genre-event", "ProjectReviewRequested", Instant.now(), 1L, snapshot))
                .isInstanceOf(BusinessException.class);
    }
}

package com.stove.review.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.time.Instant;

public interface ReviewCaseRepository extends JpaRepository<ReviewCase, Long>, JpaSpecificationExecutor<ReviewCase> {
    List<ReviewCase> findBySubmissionIdOrderByReviewType(Long submissionId);
    List<ReviewCase> findTop100ByStatusInAndDueAtBeforeOrderByDueAtAsc(
            List<ReviewCaseStatus> statuses, Instant now);
}

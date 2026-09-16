package com.stove.review.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewCaseRepository extends JpaRepository<ReviewCase, Long> {
    List<ReviewCase> findBySubmissionIdOrderByReviewType(Long submissionId);
}

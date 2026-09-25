package com.stove.review.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewDecisionHistoryRepository extends JpaRepository<ReviewDecisionHistory, Long> {
    List<ReviewDecisionHistory> findByReviewCaseIdOrderByIdAsc(Long reviewCaseId);
}

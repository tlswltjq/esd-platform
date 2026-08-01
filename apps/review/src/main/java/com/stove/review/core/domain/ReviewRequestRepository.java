package com.stove.review.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, Long> {

    Optional<ReviewRequest> findByProductCode(String productCode);

    List<ReviewRequest> findByStatusOrderByIdAsc(ReviewStatus status);
}

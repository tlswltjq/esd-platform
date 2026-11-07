package com.stove.order.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpiredReviewClosureRepository extends JpaRepository<ExpiredReviewClosure, Long> {
    Optional<ExpiredReviewClosure> findByEventId(String eventId);
    Optional<ExpiredReviewClosure> findByOrderNo(String orderNo);
}

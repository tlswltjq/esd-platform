package com.stove.review.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewWithdrawalRepository extends JpaRepository<ReviewWithdrawal, Long> {
    List<ReviewWithdrawal> findByRequestIdAndResolvedFalseOrderByWithdrawalIdAsc(Long requestId);
}

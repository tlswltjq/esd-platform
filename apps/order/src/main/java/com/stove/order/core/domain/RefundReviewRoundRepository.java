package com.stove.order.core.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundReviewRoundRepository extends JpaRepository<RefundReviewRound, Long> {
    Optional<RefundReviewRound> findByReviewId(String reviewId);
    Optional<RefundReviewRound> findByApplicationRequestId(String applicationRequestId);
    Optional<RefundReviewRound> findByDecisionRequestId(String decisionRequestId);
    Optional<RefundReviewRound> findByApprovalStop_RequestId(String requestId);
    Optional<RefundReviewRound> findByApprovalStop_SuspensionRequestId(String suspensionRequestId);
    boolean existsByPreviousReviewId(String previousReviewId);
    Page<RefundReviewRound> findByRefundIdOrderByReviewNoAsc(String refundId, Pageable page);
}

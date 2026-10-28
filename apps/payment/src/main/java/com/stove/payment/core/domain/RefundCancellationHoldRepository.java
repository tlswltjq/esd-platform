package com.stove.payment.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundCancellationHoldRepository extends JpaRepository<RefundCancellationHold, Long> {
    Optional<RefundCancellationHold> findByHoldId(String holdId);
    Optional<RefundCancellationHold> findByAttemptIdAndClaimId(String attemptId, String claimId);
    Optional<RefundCancellationHold> findByResumeRequestId(String requestId);
}

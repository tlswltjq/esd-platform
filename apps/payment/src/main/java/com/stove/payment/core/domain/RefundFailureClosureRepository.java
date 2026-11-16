package com.stove.payment.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundFailureClosureRepository extends JpaRepository<RefundFailureClosure, Long> {
    Optional<RefundFailureClosure> findByRequestId(String requestId);
    Optional<RefundFailureClosure> findByAttemptId(String attemptId);
}

package com.stove.payment.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundClosureRepository extends JpaRepository<RefundClosure, Long> {
    Optional<RefundClosure> findByRequestId(String requestId);
    Optional<RefundClosure> findByAttemptId(String attemptId);
}

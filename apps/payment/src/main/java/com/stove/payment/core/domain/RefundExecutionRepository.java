package com.stove.payment.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundExecutionRepository extends JpaRepository<RefundExecution, Long> {
    Optional<RefundExecution> findByAttemptId(String attemptId);
    Optional<RefundExecution> findByRequestEventId(String requestEventId);
    Optional<RefundExecution> findFirstByPaymentIdOrderByIdAsc(String paymentId);
    Optional<RefundExecution> findFirstByRefundIdOrderByIdAsc(String refundId);
}

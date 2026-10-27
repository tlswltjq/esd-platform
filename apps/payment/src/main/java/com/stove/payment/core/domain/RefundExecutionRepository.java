package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RefundExecutionRepository extends JpaRepository<RefundExecution, Long> {
    Optional<RefundExecution> findByAttemptId(String attemptId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundExecution r where r.attemptId=:attemptId")
    Optional<RefundExecution> lockByAttemptId(String attemptId);
    Optional<RefundExecution> findByRequestEventId(String requestEventId);
    Optional<RefundExecution> findFirstByPaymentIdOrderByIdAsc(String paymentId);
    Optional<RefundExecution> findFirstByRefundIdOrderByIdAsc(String refundId);
}

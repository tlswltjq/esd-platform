package com.stove.order.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundAttemptRepository extends JpaRepository<RefundAttempt, Long> {
    Optional<RefundAttempt> findByAttemptId(String attemptId);
    Optional<RefundAttempt> findBySuspensionEventId(String suspensionEventId);
    boolean existsByRefundId(String refundId);
    List<RefundAttempt> findByRefundIdOrderByAttemptNoAsc(String refundId);
}

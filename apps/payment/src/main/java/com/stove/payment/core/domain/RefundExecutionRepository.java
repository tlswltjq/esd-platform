package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
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
    @Query("select r.attemptId from RefundExecution r, Payment p where p.paymentId=r.paymentId "
            + "and p.provider='PORTONE_TEST' and r.refundConfirmedAt is null and r.status='PENDING' and r.lookupSchedule.nextAt is not null "
            + "and (r.lookupSchedule.nextAt<=:at or r.lookupSchedule.wakeAt<=:at) "
            + "and (r.lookupSchedule.leaseUntil is null or r.lookupSchedule.leaseUntil<=:at) "
            + "order by r.lookupSchedule.nextAt, r.id")
    List<String> findDueLookups(Instant at, Pageable page);
}

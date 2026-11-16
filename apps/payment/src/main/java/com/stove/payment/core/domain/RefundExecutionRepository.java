package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RefundExecutionRepository extends JpaRepository<RefundExecution, Long> {
    @Query("select count(r) from RefundExecution r, Payment p where p.paymentId=r.paymentId and p.provider='PORTONE_TEST' "
            + "and r.status='PENDING' and r.refundConfirmedAt is null and r.firstCancellationPreparedAt<=:preparedBy")
    long countDelayedForAdministration(Instant preparedBy);

    @Query("select r from RefundExecution r, Payment p where p.paymentId=r.paymentId and p.provider='PORTONE_TEST' "
            + "and r.status='PENDING' and r.refundConfirmedAt is null and r.firstCancellationPreparedAt<=:preparedBy")
    Page<RefundExecution> findDelayedForAdministration(Instant preparedBy, Pageable page);

    /** Decision 127: money confirmed by a full-cancellation lookup, not attributed to our attempt, and not closed by an operator. */
    String UNATTRIBUTED_OPEN = " from RefundExecution r, Payment p where p.paymentId=r.paymentId and p.provider='PORTONE_TEST' "
            + "and r.status='PENDING' and r.refundConfirmedAt is not null and r.refundConfirmationSource='PG_QUERY_UNATTRIBUTED' "
            + "and not exists (select c.id from RefundClosure c where c.attemptId=r.attemptId)";

    @Query("select count(r)" + UNATTRIBUTED_OPEN)
    long countUnattributedOpen();

    @Query(value = "select r" + UNATTRIBUTED_OPEN, countQuery = "select count(r)" + UNATTRIBUTED_OPEN)
    Page<RefundExecution> findUnattributedOpen(Pageable page);

    Optional<RefundExecution> findByAttemptId(String attemptId);
    @Query("select r.paymentId from RefundExecution r where r.attemptId=:attemptId")
    Optional<String> findPaymentIdByAttemptId(String attemptId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundExecution r where r.attemptId=:attemptId")
    Optional<RefundExecution> lockByAttemptId(String attemptId);
    Optional<RefundExecution> findByPreviousAttemptId(String previousAttemptId);
    Optional<RefundExecution> findByLicenseSuspensionEventId(String eventId);
    Optional<RefundExecution> findFirstByPaymentIdAndStatusAndRefundConfirmedAtIsNullOrderByIdDesc(String paymentId, RefundExecutionStatus status);
    /** The newest execution of the payment whose read-only lookup is still scheduled: unconfirmed, or failed and watched (decision 128). */
    Optional<RefundExecution> findFirstByPaymentIdAndLookupScheduleNextAtIsNotNullOrderByIdDesc(String paymentId);

    /** Decision 128: failed executions on which a later lookup found a full cancellation; the gauge behind its alert. */
    @Query("select count(r) from RefundExecution r where r.status='FAILED' and r.lateCancellationObservedAt is not null")
    long countLateCancellationsAfterFailure();

    @Query(value = "select r from RefundExecution r where r.status='FAILED' and r.lateCancellationObservedAt is not null",
            countQuery = "select count(r) from RefundExecution r where r.status='FAILED' and r.lateCancellationObservedAt is not null")
    Page<RefundExecution> findLateCancellationsAfterFailure(Pageable page);
    Optional<RefundExecution> findByRequestEventId(String requestEventId);
    Optional<RefundExecution> findFirstByPaymentIdOrderByIdAsc(String paymentId);
    Optional<RefundExecution> findFirstByRefundIdOrderByIdAsc(String refundId);
    @Query("select r.attemptId from RefundExecution r, Payment p where p.paymentId=r.paymentId "
            + "and p.provider='PORTONE_TEST' and r.refundConfirmedAt is null and r.status in ('PENDING','FAILED') and r.lookupSchedule.nextAt is not null "
            + "and (r.lookupSchedule.nextAt<=:at or r.lookupSchedule.wakeAt<=:at) "
            + "and (r.lookupSchedule.leaseUntil is null or r.lookupSchedule.leaseUntil<=:at) "
            + "order by r.lookupSchedule.nextAt, r.id")
    List<String> findDueLookups(Instant at, Pageable page);

    @Query("select r.attemptId from RefundExecution r, Payment p where p.paymentId=r.paymentId "
            + "and p.provider='PORTONE_TEST' and r.status='PENDING' and r.refundConfirmedAt is null "
            + "and r.firstCancellationPreparedAt is not null and r.cancellationRequestBody is not null "
            + "and r.receiptCancellationId is null and r.cancellationBalanceChangedAt is null and r.automaticCancellationStoppedAt is null "
            + "and r.activeCancellationHoldId is null "
            + "and (r.cancellationSchedule.nextAt<=:at or (r.cancellationSchedule.nextAt is null and r.cancellationSchedule.lastFinishedAt is null)) "
            + "and (r.cancellationSchedule.leaseUntil is null or r.cancellationSchedule.leaseUntil<=:at) "
            + "and (r.cancellationSchedule.closureUntil is null or r.cancellationSchedule.closureUntil<=:at) "
            + "order by r.cancellationSchedule.nextAt, r.id")
    List<String> findRetryCandidates(Instant at, Pageable page);
}

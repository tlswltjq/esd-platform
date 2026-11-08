package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only operator failure closure of a stopped refund execution (decision 128). It is written only after a PG
 * re-inspection showed the original payment PAID with no cancelled amount and only FAILED cancellations, in the same
 * transaction that fails the execution and records {@code RefundExecutionFailed}. It never moves money.
 */
@Entity
@Table(name = "refund_failure_closure")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundFailureClosure {
    /** The cancellation ID {@code RefundExecutionFailed} carries when no PG cancellation is linked to the attempt. */
    public static final String UNLINKED_CANCELLATION_PREFIX = "ADMIN_FAILURE_CLOSURE:";

    /** What tied the PG evidence to the attempt. */
    public enum Evidence {
        /** The PG never returned a cancellation for this attempt (403·400, balance change, lost response). */
        NO_LINKED_CANCELLATION,
        /** The cancellation the PG returned for this attempt is FAILED in the re-inspection. */
        LINKED_CANCELLATION_FAILED
    }

    /** Why the automatic POST had stopped when the operator closed the attempt. */
    public enum StopReason { CANCELLATION_HOLD, BALANCE_CHANGED, WINDOW_ENDED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String requestId;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String attemptId;
    @Column(nullable = false, updatable = false, length = 100) private String operatorSubject;
    @Column(nullable = false, updatable = false, length = 500) private String note;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 32) private Evidence evidence;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 24) private StopReason stopReason;
    @Column(nullable = false, updatable = false, length = 64) private String snapshotHash;
    @Column(nullable = false, updatable = false) private Instant evidenceObservedAt;
    @Column(nullable = false, updatable = false) private Instant closedAt;
    @Column(nullable = false, unique = true, updatable = false, length = 64) private String failureEventId;
    @Column(nullable = false, updatable = false, length = 100) private String failedCancellationId;
    // The execution facts the first response reported, frozen so that a replay returns them unchanged.
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 20) private RefundExecutionStatus executionStatus;
    @Column(nullable = false, updatable = false) private Instant failureConfirmedAt;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "refund_failure_closure_cancellation", joinColumns = @JoinColumn(name = "closure_id"))
    @OrderBy("cancellationId")
    private List<RefundFailedCancellation> failedCancellations = new ArrayList<>();

    public static RefundFailureClosure record(RefundExecution execution, RefundFailureClosureRequest request, StopReason stopReason,
            PgRefundUncancelled observed, Instant observedAt) {
        if (execution == null || request == null || stopReason == null || observed == null || !execution.getAttemptId().equals(request.attemptId()))
            throw new IllegalArgumentException("A failure closure must identify the closed execution and its evidence");
        if (execution.getStatus() != RefundExecutionStatus.FAILED || execution.getFailureEventId() == null || execution.getFailureConfirmedAt() == null
                || !observed.snapshotHash().equals(execution.getFailureLookupHash()))
            throw new IllegalArgumentException("A failure closure records only the failure it confirmed");
        Instant at = execution.getFailureConfirmedAt();
        if (!PaymentValues.microsecond(observedAt) || at.isBefore(observedAt))
            throw new IllegalArgumentException("PG evidence observed before the closure is required");
        RefundFailureClosure closure = new RefundFailureClosure();
        closure.requestId = request.requestId(); closure.attemptId = request.attemptId();
        closure.operatorSubject = request.operatorSubject(); closure.note = request.note();
        String linked = execution.getReceiptCancellationId();
        closure.evidence = linked == null ? Evidence.NO_LINKED_CANCELLATION : Evidence.LINKED_CANCELLATION_FAILED;
        closure.stopReason = stopReason; closure.snapshotHash = observed.snapshotHash();
        closure.evidenceObservedAt = observedAt; closure.closedAt = at;
        closure.failureEventId = execution.getFailureEventId();
        closure.failedCancellationId = linked == null ? UNLINKED_CANCELLATION_PREFIX + request.requestId() : linked;
        closure.executionStatus = execution.getStatus(); closure.failureConfirmedAt = at;
        closure.failedCancellations = new ArrayList<>(observed.failedCancellations());
        return closure;
    }

    public void requireSame(RefundFailureClosureRequest request) {
        if (request == null || !requestId.equals(request.requestId()) || !attemptId.equals(request.attemptId())
                || !operatorSubject.equals(request.operatorSubject()) || !note.equals(request.note()))
            throw new BusinessException(PaymentErrorCode.REFUND_CLOSURE_CHANGED);
    }

    public List<RefundFailedCancellation> getFailedCancellations() { return List.copyOf(failedCancellations); }

    public RefundFailureClosureView view() {
        return new RefundFailureClosureView(requestId, attemptId, operatorSubject, note, evidence, stopReason,
                failedCancellations.stream().map(RefundFailedCancellation::getCancellationId).toList(), snapshotHash, evidenceObservedAt, closedAt);
    }
}

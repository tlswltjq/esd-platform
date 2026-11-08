package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only operator closure of a delayed or unattributed refund execution (decision 127). It is written only after a
 * PG re-inspection confirmed the full cancellation of the same original payment, in the same transaction that completes
 * the execution (when it was not complete yet). It records the attribution judgment; it never moves money.
 */
@Entity
@Table(name = "refund_closure")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundClosure {
    /** What the closure did to the execution besides recording the judgment. */
    public enum Outcome {
        /** The execution was unconfirmed; the closure completed it through the existing confirmation path. */
        COMPLETED,
        /** The execution was already confirmed as unattributed; only the judgment was recorded. */
        RECORDED
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String requestId;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String attemptId;
    @Column(nullable = false, updatable = false, length = 100) private String operatorSubject;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 24) private RefundAttribution attribution;
    @Column(nullable = false, updatable = false, length = 500) private String note;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 16) private Outcome outcome;
    @Column(nullable = false, updatable = false) private Instant evidenceRefundedAt;
    @Column(nullable = false, updatable = false) private Instant evidenceObservedAt;
    @Column(nullable = false, updatable = false) private Instant closedAt;
    // The execution facts the first response reported, frozen so that a replay returns them unchanged (R14.1).
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 20) private RefundExecutionStatus executionStatus;
    @Column(nullable = false, updatable = false) private Instant refundConfirmedAt;
    @Column(nullable = false, updatable = false, length = 30) private String refundConfirmationSource;

    public static RefundClosure record(RefundExecution execution, RefundClosureRequest request, Outcome outcome, Instant observedAt, Instant at) {
        if (execution == null || request == null || outcome == null || !execution.getAttemptId().equals(request.attemptId()))
            throw new IllegalArgumentException("A closure must identify the closed execution and its outcome");
        if (!execution.unattributed() || execution.getProviderRefundedAt() == null)
            throw new IllegalArgumentException("A closure records only a confirmed, unattributed full cancellation");
        if (!PaymentValues.microsecond(observedAt) || !PaymentValues.microsecond(at) || at.isBefore(observedAt))
            throw new IllegalArgumentException("PG evidence observed before the closure is required");
        RefundClosure closure = new RefundClosure();
        closure.requestId = request.requestId(); closure.attemptId = request.attemptId();
        closure.operatorSubject = request.operatorSubject(); closure.attribution = request.attribution(); closure.note = request.note();
        closure.outcome = outcome; closure.evidenceRefundedAt = execution.getProviderRefundedAt();
        closure.evidenceObservedAt = observedAt; closure.closedAt = at;
        closure.executionStatus = execution.getStatus(); closure.refundConfirmedAt = execution.getRefundConfirmedAt();
        closure.refundConfirmationSource = execution.getRefundConfirmationSource();
        return closure;
    }

    public void requireSame(RefundClosureRequest request) {
        if (request == null || !requestId.equals(request.requestId()) || !attemptId.equals(request.attemptId())
                || !operatorSubject.equals(request.operatorSubject()) || attribution != request.attribution() || !note.equals(request.note()))
            throw new BusinessException(PaymentErrorCode.REFUND_CLOSURE_CHANGED);
    }

    public RefundClosureView view() {
        return new RefundClosureView(requestId, attemptId, operatorSubject, attribution, note, outcome, evidenceRefundedAt, evidenceObservedAt, closedAt);
    }
}

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
 * Append-only operator closure of a held approval (decision 115). It is written only together with the approval and
 * purchase transition and only for evidence re-inspected from the PG at closure time.
 */
@Entity
@Table(name = "approval_closure")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalClosure {
    /** Failure reason code published to Order when a closure ends an unresolved purchase. */
    public static final String REASON_CODE = "REVIEW_CLOSED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String requestId;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String approvalId;
    @Column(nullable = false, updatable = false, length = 100) private String operatorSubject;
    @Column(nullable = false, updatable = false, length = 500) private String note;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 16) private ApprovalObservation.State evidenceState;
    @Column(nullable = false, updatable = false) private Instant evidenceObservedAt;
    @Column(nullable = false, updatable = false) private Instant closedAt;

    public static ApprovalClosure record(PaymentApproval approval, ApprovalClosureRequest request, ApprovalObservation evidence,
            Instant observedAt, Instant at) {
        if (approval == null || request == null || !approval.getApprovalId().equals(request.approvalId()))
            throw new IllegalArgumentException("A closure must identify the held approval");
        if (approval.getStatus() != PaymentApprovalStatus.REVIEW_REQUIRED) throw new BusinessException(PaymentErrorCode.APPROVAL_NOT_HELD);
        if (evidence == null || !evidence.closesReview() || !PaymentValues.microsecond(observedAt) || !PaymentValues.microsecond(at)
                || at.isBefore(observedAt))
            throw new IllegalArgumentException("Closable PG evidence observed before the closure is required");
        ApprovalClosure closure = new ApprovalClosure();
        closure.requestId = request.requestId(); closure.approvalId = request.approvalId();
        closure.operatorSubject = request.operatorSubject(); closure.note = request.note();
        closure.evidenceState = evidence.state(); closure.evidenceObservedAt = observedAt; closure.closedAt = at;
        return closure;
    }

    public void requireSame(ApprovalClosureRequest request) {
        if (request == null || !requestId.equals(request.requestId()) || !approvalId.equals(request.approvalId())
                || !operatorSubject.equals(request.operatorSubject()) || !note.equals(request.note()))
            throw new BusinessException(PaymentErrorCode.APPROVAL_CLOSURE_CHANGED);
    }

    /** A bounded technical reason; it is not the customer message, which decision 115 leaves undecided. */
    public String reason() {
        return "Payment approval review closed with PG evidence " + evidenceState;
    }

    public ApprovalClosureView view() {
        return new ApprovalClosureView(requestId, approvalId, operatorSubject, note, evidenceState, evidenceObservedAt, closedAt);
    }
}

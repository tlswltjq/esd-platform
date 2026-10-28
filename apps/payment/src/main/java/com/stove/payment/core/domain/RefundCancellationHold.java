package com.stove.payment.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One immutable POST rejection observation, with at most one operator resume command. */
@Entity @Table(name = "refund_cancellation_hold")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundCancellationHold {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String holdId;
    @Column(nullable = false, updatable = false, length = 36) private String attemptId;
    @Column(nullable = false, updatable = false, length = 36) private String claimId;
    @Column(nullable = false, updatable = false) private Instant claimedAt;
    @Column(nullable = false, updatable = false) private Instant observedAt;
    @Column(nullable = false, updatable = false, length = 40) private String reason;
    @Column(nullable = false, updatable = false) private boolean activated;
    @Column(unique = true, length = 36) private String resumeRequestId;
    private Instant resumedAt;
    @Column(length = 100) private String operatorSubject;
    @Column(length = 500) private String resumeNote;

    public static RefundCancellationHold forbidden(String attemptId, RefundCancellationClaim claim, Instant at, boolean activated) {
        return rejection(attemptId, claim, at, activated, "PG_REQUEST_FORBIDDEN");
    }

    public static RefundCancellationHold invalidRequest(String attemptId, RefundCancellationClaim claim, Instant at, boolean activated) {
        return rejection(attemptId, claim, at, activated, "PG_REQUEST_INVALID");
    }

    private static RefundCancellationHold rejection(String attemptId, RefundCancellationClaim claim, Instant at, boolean activated, String reason) {
        if (!PaymentValues.uuid(attemptId) || claim == null || !PaymentValues.microsecond(at) || at.isBefore(claim.startedAt()))
            throw new IllegalArgumentException("A rejection observation must identify its execution and sending owner");
        RefundCancellationHold hold = new RefundCancellationHold();
        hold.holdId = UUID.randomUUID().toString(); hold.attemptId = attemptId; hold.claimId = claim.token();
        hold.claimedAt = claim.startedAt(); hold.observedAt = at; hold.activated = activated;
        hold.reason = reason; return hold;
    }

    public void requireObservation(String attemptId, RefundCancellationClaim claim) {
        if (!this.attemptId.equals(attemptId) || claim == null || !claimId.equals(claim.token()) || !claimedAt.equals(claim.startedAt()))
            throw new IllegalArgumentException("Rejection observation belongs to another execution or owner");
    }

    public boolean recordResume(RefundCancellationResumeRequest request, Instant at) {
        if (request == null || !PaymentValues.microsecond(at)) throw new IllegalArgumentException("A resume command and precise time are required");
        if (!activated || !attemptId.equals(request.attemptId()) || !holdId.equals(request.holdId()) || at.isBefore(observedAt))
            throw new BusinessException(ErrorCode.CONFLICT, "재개할 수 있는 원래 중단 근거가 아닙니다.");
        if (resumeRequestId != null) { requireSameResume(request); return false; }
        resumeRequestId = request.requestId(); resumedAt = at; operatorSubject = request.operatorSubject(); resumeNote = request.note();
        return true;
    }

    public void requireSameResume(RefundCancellationResumeRequest request) {
        if (request == null || resumeRequestId == null || !resumeRequestId.equals(request.requestId())
                || !attemptId.equals(request.attemptId()) || !holdId.equals(request.holdId())
                || !Objects.equals(operatorSubject, request.operatorSubject()) || !Objects.equals(resumeNote, request.note()))
            throw new BusinessException(ErrorCode.CONFLICT, "이미 접수한 재개 지시의 근거가 다릅니다.");
    }
    public RefundCancellationResumeReceipt receipt() {
        return new RefundCancellationResumeReceipt(resumeRequestId, attemptId, holdId, resumedAt);
    }
}

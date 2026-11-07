package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.ApprovalClosureView;
import com.stove.payment.core.domain.ApprovalObservation;
import java.time.Instant;

/** The append-only operator closure record. It never contains a token, the protected payload or ciphertext. */
public record ApprovalClosureRecordResponse(String requestId, String operatorSubject, String note, ApprovalObservation.State evidenceState,
        Instant evidenceObservedAt, Instant closedAt) {
    public static ApprovalClosureRecordResponse from(ApprovalClosureView view) {
        return view == null ? null : new ApprovalClosureRecordResponse(view.requestId(), view.operatorSubject(), view.note(),
                view.evidenceState(), view.evidenceObservedAt(), view.closedAt());
    }
}

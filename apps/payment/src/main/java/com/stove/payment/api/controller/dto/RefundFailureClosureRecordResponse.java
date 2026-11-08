package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundFailureClosure;
import com.stove.payment.core.domain.RefundFailureClosureView;
import java.time.Instant;
import java.util.List;

/** The append-only operator failure closure record of a refund execution (decision 128). It never contains PG keys or bodies. */
public record RefundFailureClosureRecordResponse(String requestId, String operatorSubject, String note, RefundFailureClosure.Evidence evidence,
        RefundFailureClosure.StopReason stopReason, List<String> failedCancellationIds, String snapshotHash, Instant evidenceObservedAt,
        Instant closedAt) {
    public static RefundFailureClosureRecordResponse from(RefundFailureClosureView view) {
        return view == null ? null : new RefundFailureClosureRecordResponse(view.requestId(), view.operatorSubject(), view.note(),
                view.evidence(), view.stopReason(), view.failedCancellationIds(), view.snapshotHash(), view.evidenceObservedAt(), view.closedAt());
    }
}

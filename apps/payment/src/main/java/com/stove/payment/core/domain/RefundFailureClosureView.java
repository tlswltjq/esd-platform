package com.stove.payment.core.domain;

import java.time.Instant;
import java.util.List;

public record RefundFailureClosureView(String requestId, String attemptId, String operatorSubject, String note,
        RefundFailureClosure.Evidence evidence, RefundFailureClosure.StopReason stopReason, List<String> failedCancellationIds,
        String snapshotHash, Instant evidenceObservedAt, Instant closedAt) {
    public RefundFailureClosureView { failedCancellationIds = List.copyOf(failedCancellationIds); }
}

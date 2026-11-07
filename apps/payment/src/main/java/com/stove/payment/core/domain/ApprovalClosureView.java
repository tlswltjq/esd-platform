package com.stove.payment.core.domain;

import java.time.Instant;

public record ApprovalClosureView(String requestId, String approvalId, String operatorSubject, String note,
        ApprovalObservation.State evidenceState, Instant evidenceObservedAt, Instant closedAt) { }

package com.stove.payment.core.domain;

import java.time.Instant;

public record RefundClosureView(String requestId, String attemptId, String operatorSubject, RefundAttribution attribution,
        String note, RefundClosure.Outcome outcome, Instant evidenceRefundedAt, Instant evidenceObservedAt, Instant closedAt) { }

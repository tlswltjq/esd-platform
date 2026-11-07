package com.stove.payment.core.domain;

import java.time.Instant;

public record ApprovalReviewView(String approvalId, String orderNo, String paymentId, String reason, boolean admitted, Instant receivedAt, Instant admittedAt, Instant firstPreparedAt, Instant reviewStartedAt, Long heldSeconds, ApprovalObservation.State latestPgState, Instant latestPgObservedAt, long investigationCount, PaymentApprovalStatus status, Instant lastInspectedAt, String lastInspectionError, ApprovalClosureView closure) { }

package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.ApprovalReviewView;
import java.time.Instant;
import com.stove.payment.core.domain.PaymentApprovalStatus;

public record ApprovalReviewResponse(String approvalId, String orderNo, String paymentId, String reason, boolean admitted, Instant receivedAt, Instant admittedAt, Instant firstPreparedAt, Instant reviewStartedAt, Long heldSeconds, ApprovalObservation.State latestPgState, Instant latestPgObservedAt, long investigationCount, PaymentApprovalStatus status, Instant lastInspectedAt, String lastInspectionError, ApprovalClosureRecordResponse closure) {
    public static ApprovalReviewResponse from(ApprovalReviewView v) {
        return new ApprovalReviewResponse(v.approvalId(), v.orderNo(), v.paymentId(), v.reason(), v.admitted(), v.receivedAt(), v.admittedAt(), v.firstPreparedAt(), v.reviewStartedAt(), v.heldSeconds(), v.latestPgState(), v.latestPgObservedAt(), v.investigationCount(), v.status(), v.lastInspectedAt(), v.lastInspectionError(), ApprovalClosureRecordResponse.from(v.closure()));
    }
}

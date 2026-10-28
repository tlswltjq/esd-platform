package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundApprovalStopReceipt;
import java.time.Instant;

public record RefundApprovalStopResponse(String requestId, String refundId, String reviewId, String operatorSubject, String reason, Instant receivedAt) {
    public static RefundApprovalStopResponse from(RefundApprovalStopReceipt receipt) {
        return new RefundApprovalStopResponse(receipt.requestId(),receipt.refundId(),receipt.reviewId(),receipt.operatorSubject(),receipt.reason(),receipt.receivedAt());
    }
}

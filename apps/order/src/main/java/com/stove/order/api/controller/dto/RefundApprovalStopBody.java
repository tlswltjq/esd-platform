package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundApprovalStopRequest;

public record RefundApprovalStopBody(String requestId, String reviewId, String reason) {
    public RefundApprovalStopRequest toInput(String refundId, String operator) { return new RefundApprovalStopRequest(requestId,refundId,reviewId,operator,reason); }
}

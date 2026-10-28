package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReviewDecision;
import com.stove.order.core.domain.RefundReviewReceipt;
import java.time.Instant;

public record AdminRefundDecisionResponse(String requestId, String refundId, String operatorSubject,
        RefundReviewDecision decision, String reason, Instant decidedAt) {
    public static AdminRefundDecisionResponse from(RefundReviewReceipt receipt) {
        return receipt == null ? null : new AdminRefundDecisionResponse(receipt.requestId(), receipt.refundId(),
                receipt.operatorSubject(), receipt.decision(), receipt.reason(), receipt.decidedAt());
    }
}

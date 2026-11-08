package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.domain.RefundFailureClosureReceipt;
import java.time.Instant;

public record RefundFailureClosureResponse(String attemptId, String refundId, String orderNo, String paymentId,
        RefundExecutionStatus executionStatus, Instant failureConfirmedAt, RefundFailureClosureRecordResponse failureClosure) {
    public static RefundFailureClosureResponse from(RefundFailureClosureReceipt receipt) {
        return new RefundFailureClosureResponse(receipt.closure().attemptId(), receipt.refundId(), receipt.orderNo(), receipt.paymentId(),
                receipt.executionStatus(), receipt.failureConfirmedAt(), RefundFailureClosureRecordResponse.from(receipt.closure()));
    }
}

package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundClosureReceipt;
import com.stove.payment.core.domain.RefundExecutionStatus;
import java.time.Instant;

public record RefundClosureResponse(String attemptId, String refundId, String orderNo, String paymentId,
        RefundExecutionStatus executionStatus, Instant refundConfirmedAt, String refundConfirmationSource, RefundClosureRecordResponse closure) {
    public static RefundClosureResponse from(RefundClosureReceipt receipt) {
        return new RefundClosureResponse(receipt.closure().attemptId(), receipt.refundId(), receipt.orderNo(), receipt.paymentId(),
                receipt.executionStatus(), receipt.refundConfirmedAt(), receipt.refundConfirmationSource(),
                RefundClosureRecordResponse.from(receipt.closure()));
    }
}

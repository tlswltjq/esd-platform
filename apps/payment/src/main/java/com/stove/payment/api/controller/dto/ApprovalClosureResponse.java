package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.ApprovalClosureReceipt;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.PaymentStatus;

public record ApprovalClosureResponse(String approvalId, String orderNo, String paymentId, PaymentApprovalStatus approvalStatus,
        PaymentStatus paymentStatus, ApprovalClosureRecordResponse closure) {
    public static ApprovalClosureResponse from(ApprovalClosureReceipt receipt) {
        return new ApprovalClosureResponse(receipt.closure().approvalId(), receipt.orderNo(), receipt.paymentId(), receipt.approvalStatus(),
                receipt.paymentStatus(), ApprovalClosureRecordResponse.from(receipt.closure()));
    }
}

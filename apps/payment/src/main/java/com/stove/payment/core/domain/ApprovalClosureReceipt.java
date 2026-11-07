package com.stove.payment.core.domain;

/** The original closure result. Approval and purchase states are terminal after a closure, so a replay reads the same values. */
public record ApprovalClosureReceipt(ApprovalClosureView closure, String orderNo, String paymentId,
        PaymentApprovalStatus approvalStatus, PaymentStatus paymentStatus) { }

package com.stove.order.core.domain;

import java.time.Instant;

public record RefundApprovalStopReceipt(String requestId, String refundId, String reviewId, String operatorSubject,
        String reason, Instant receivedAt) { }

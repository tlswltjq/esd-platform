package com.stove.order.core.domain;

import java.time.Instant;

public record RefundReviewReceipt(String requestId, String refundId, String operatorSubject,
        RefundReviewDecision decision, String reason, Instant decidedAt) { }

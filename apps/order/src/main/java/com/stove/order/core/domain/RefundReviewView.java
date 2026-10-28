package com.stove.order.core.domain;

import java.time.Instant;

public record RefundReviewView(String refundId, String orderNo, String buyerSubject, String productCode,
        String title, long amount, String currency, Instant paymentConfirmedAt, Instant receivedAt,
        RefundStatus status, RefundReviewReceipt review) { }

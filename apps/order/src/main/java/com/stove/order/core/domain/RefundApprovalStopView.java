package com.stove.order.core.domain;

import java.time.Instant;

public record RefundApprovalStopView(RefundApprovalStopReceipt receipt, Instant restorationRequestedAt,
        Instant restoredAt, Instant reopenedAt, String nextReviewId) { }

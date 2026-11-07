package com.stove.payment.core.domain;

import java.util.List;

public final class ApprovalReviewReason {
    public static final List<String> ALL = List.of("PAID_WITHOUT_ADMISSION", "PG_REQUEST_REJECTED", "PG_INVALID_EVIDENCE", "ORDER_ADMISSION_CONFLICT", "ORDER_EXPIRY_CONFLICT", "OTHER");
    private ApprovalReviewReason() { }
    public static String bounded(String reason) { return reason != null && ALL.contains(reason) ? reason : "OTHER"; }
}

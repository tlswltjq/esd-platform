package com.stove.payment.core.domain;

import java.util.List;

public final class ApprovalReviewReason {
    public static final List<String> ALL = List.of("PAID_WITHOUT_ADMISSION", "PG_REQUEST_REJECTED", "PG_INVALID_EVIDENCE", "ORDER_ADMISSION_CONFLICT", "ORDER_EXPIRY_CONFLICT", "PG_CANCELLED", "PG_PENDING_AT_ORDER_EXPIRY", "CONFIRM_WINDOW_EXPIRED", "OTHER");
    public static final List<String> KNOWN = List.of("PAID_WITHOUT_ADMISSION", "PG_REQUEST_REJECTED", "PG_INVALID_EVIDENCE", "ORDER_ADMISSION_CONFLICT", "ORDER_EXPIRY_CONFLICT", "PG_CANCELLED", "PG_PENDING_AT_ORDER_EXPIRY", "CONFIRM_WINDOW_EXPIRED");
    private ApprovalReviewReason() { }
}

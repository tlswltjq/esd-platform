package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

final class RefundReviewValues {
    private RefundReviewValues() { }
    static void requireReviewId(String reviewId) {
        if (reviewId == null || !reviewId.matches(OrderValues.UUID_PATTERN)) throw invalid();
    }
    static void requireBuyer(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 100) throw invalid();
    }
    static void requireIdentity(String requestId, String refundId) {
        if (requestId == null || !requestId.matches(OrderValues.UUID_PATTERN)
                || refundId == null || !refundId.matches(OrderValues.UUID_PATTERN)) throw invalid();
    }
    static void requireOperator(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 100) throw invalid();
    }
    static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw invalid();
    }
    static void requireTime(Instant at) {
        if (at == null || at.getNano() % 1000 != 0) throw invalid();
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST, "환불 판정의 식별자·담당자·사유·시각이 올바르지 않습니다."); }
}

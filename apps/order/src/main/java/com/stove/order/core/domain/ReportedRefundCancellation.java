package com.stove.order.core.domain;

public record ReportedRefundCancellation(String cancellationId, long totalAmount, Long pointAmount) {
    public ReportedRefundCancellation {
        if (cancellationId == null || cancellationId.isBlank() || cancellationId.length() > 100 || totalAmount <= 0
                || (pointAmount != null && (pointAmount < 0 || pointAmount > totalAmount)))
            throw new RefundCompletionRejected("보고된 취소 금액의 근거가 올바르지 않습니다.");
    }
}

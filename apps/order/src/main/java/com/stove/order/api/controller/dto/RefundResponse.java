package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record RefundResponse(String refundId, String orderNo, long amount, String currency,
        Status status, Instant receivedAt, Instant completedAt, String rejectionReason,
        @Schema(description = "확정 실패와 실제 이용권 복구가 끝나 재시도 가능한 현재 시도 ID. 그 외 상태는 null", format = "uuid", nullable = true)
        String retryableAttemptId) {
    public enum Status { PENDING_REVIEW, PROCESSING, COMPLETED, FAILED, REJECTED }

    public static RefundResponse from(RefundView view) {
        Status status = switch (view.status()) {
            case PENDING_REVIEW -> Status.PENDING_REVIEW;
            case COMPLETED -> Status.COMPLETED;
            case FAILED -> Status.FAILED;
            case REJECTED -> Status.REJECTED;
            case PENDING_LICENSE_GRANT, PENDING_LICENSE_SUSPENSION, PENDING_PAYMENT_REFUND, PENDING_LICENSE_RESTORATION -> Status.PROCESSING;
        };
        return new RefundResponse(view.refundId(), view.orderNo(), view.amount(), view.currency(), status, view.receivedAt(), view.completedAt(), view.rejectionReason(), view.retryableAttemptId());
    }
}

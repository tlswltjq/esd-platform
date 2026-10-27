package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

/** A committed dispatch preparation; callers must recheck time immediately before HTTP. */
public record RefundCancellationPermission(RefundExecutionRequest request, Instant firstPreparedAt,
        boolean automaticCancellationStopped) {
    public RefundCancellationPermission {
        if (request == null || !PaymentValues.microsecond(firstPreparedAt)) {
            throw new IllegalArgumentException("An original refund request and preparation time are required");
        }
    }

    public String idempotencyKey() { return request.attemptId(); }

    public Instant expiresAt() { return RefundCancellationWindow.deadline(firstPreparedAt); }

    public boolean maySendAt(Instant at) {
        requireTime(at);
        return !automaticCancellationStopped && !at.isBefore(firstPreparedAt) && at.isBefore(expiresAt());
    }

    public boolean requiresOperatorReviewAt(Instant at) {
        requireTime(at);
        return automaticCancellationStopped || !at.isBefore(expiresAt());
    }

    public void requireCancellationAllowedAt(Instant at) {
        if (!maySendAt(at)) throw new BusinessException(ErrorCode.CONFLICT, "자동 취소 요청이 허용되지 않습니다.");
    }

    private static void requireTime(Instant at) {
        if (!PaymentValues.microsecond(at)) throw new IllegalArgumentException("A microsecond-precision time is required");
    }
}

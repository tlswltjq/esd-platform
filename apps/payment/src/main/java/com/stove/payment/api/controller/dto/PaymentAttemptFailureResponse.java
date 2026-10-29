package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.FailedPaymentAttempt;
import java.time.Instant;

public record PaymentAttemptFailureResponse(String attemptId, String code, String message, Instant requestedAt, Instant failedAt, Instant observedAt) {
    public static PaymentAttemptFailureResponse from(FailedPaymentAttempt attempt) {
        return new PaymentAttemptFailureResponse(attempt.getAttemptId(), "PAYMENT_ATTEMPT_FAILED",
                "결제 시도가 실패했습니다. 결제 대기 중인 같은 주문에서 다시 결제할 수 있습니다.",
                attempt.getRequestedAt(), attempt.getFailedAt(), attempt.getObservedAt());
    }
}

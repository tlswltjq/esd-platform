package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** A committed dispatch preparation; callers must recheck time immediately before HTTP. */
public record RefundCancellationPermission(RefundExecutionRequest request, Instant firstPreparedAt,
        boolean automaticCancellationStopped, List<PgRefundCancellationReceipt> previousFailures, Instant dispatchLeaseUntil) {
    public RefundCancellationPermission {
        if (request == null || !PaymentValues.microsecond(firstPreparedAt)) {
            throw new IllegalArgumentException("An original refund request and preparation time are required");
        }
        if (previousFailures == null) throw new IllegalArgumentException("Previous failure evidence is required");
        if (dispatchLeaseUntil != null && (!PaymentValues.microsecond(dispatchLeaseUntil) || !dispatchLeaseUntil.isAfter(firstPreparedAt)))
            throw new IllegalArgumentException("Dispatch lease must follow original preparation");
        previousFailures = List.copyOf(previousFailures);
        Set<String> ids = new HashSet<>(); Set<String> attempts = new HashSet<>();
        for (PgRefundCancellationReceipt failure : previousFailures) {
            if (failure.state() != PgRefundCancellationReceipt.State.FAILED || !ids.add(failure.cancellationId())
                    || !attempts.add(failure.attemptId()) || failure.attemptId().equals(request.attemptId())
                    || !failure.purchase().paymentId().equals(request.paymentId()) || !failure.purchase().orderNo().equals(request.orderNo())
                    || failure.purchase().amount() != request.amount() || !failure.purchase().currency().equals(request.currency()))
                throw new IllegalArgumentException("Previous failed cancellations do not match this purchase");
        }
        if (!previousFailures.isEmpty() && (request.previousAttemptId() == null || !previousFailures.getFirst().attemptId().equals(request.previousAttemptId())))
            throw new IllegalArgumentException("Failure evidence must start with the explicit predecessor");
    }

    public RefundCancellationPermission(RefundExecutionRequest request, Instant firstPreparedAt, boolean automaticCancellationStopped) {
        this(request, firstPreparedAt, automaticCancellationStopped, List.of(), null);
    }

    public RefundCancellationPermission(RefundExecutionRequest request, Instant firstPreparedAt, boolean automaticCancellationStopped,
            List<PgRefundCancellationReceipt> previousFailures) {
        this(request, firstPreparedAt, automaticCancellationStopped, previousFailures, null);
    }

    public String idempotencyKey() { return request.attemptId(); }

    public Instant expiresAt() { return RefundCancellationWindow.deadline(firstPreparedAt); }

    public boolean maySendAt(Instant at) {
        requireTime(at);
        return !automaticCancellationStopped && (request.previousAttemptId() == null || !previousFailures.isEmpty())
                && !at.isBefore(firstPreparedAt) && at.isBefore(expiresAt()) && (dispatchLeaseUntil == null || at.isBefore(dispatchLeaseUntil));
    }

    public boolean requiresOperatorReviewAt(Instant at) {
        requireTime(at);
        return automaticCancellationStopped || (request.previousAttemptId() != null && previousFailures.isEmpty()) || !at.isBefore(expiresAt());
    }

    public void requireCancellationAllowedAt(Instant at) {
        if (!maySendAt(at)) throw new BusinessException(ErrorCode.CONFLICT, "자동 취소 요청이 허용되지 않습니다.");
    }

    private static void requireTime(Instant at) {
        if (!PaymentValues.microsecond(at)) throw new IllegalArgumentException("A microsecond-precision time is required");
    }
}

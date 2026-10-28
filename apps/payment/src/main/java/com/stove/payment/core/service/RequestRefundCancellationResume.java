package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.domain.RefundCancellationResumeRequest;
import com.stove.payment.core.domain.RefundCancellationResumeReceipt;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Trusted operator use case; HTTP authentication/role enforcement belongs at its future API boundary. */
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RequestRefundCancellationResume {
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final RefundCancellationHoldRepository holds;
    private final Clock clock;

    public RefundCancellationResumeReceipt execute(RefundCancellationResumeRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        RefundExecution received = executions.findByAttemptId(request.attemptId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Payment payment = payments.lockByPaymentId(received.getPaymentId()).orElseThrow();
        RefundExecution execution = executions.lockByAttemptId(request.attemptId()).orElseThrow();
        payment.requireRefundRequest(execution.request());
        if (!"PORTONE_TEST".equals(payment.getProvider())) throw new BusinessException(ErrorCode.CONFLICT);
        RefundCancellationHold known = holds.findByResumeRequestId(request.requestId()).orElse(null);
        if (known != null) { known.requireSameResume(request); return known.receipt(); }
        RefundCancellationHold hold = holds.findByHoldId(request.holdId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!hold.getAttemptId().equals(request.attemptId()) || hold.getResumeRequestId() != null)
            throw new BusinessException(ErrorCode.CONFLICT, "지정한 중단의 새 재개 지시가 아닙니다.");
        Instant at = clock.instant();
        execution.resumeCancellation(hold.getHoldId(), at);
        hold.recordResume(request, at);
        executions.flush();
        return hold.receipt();
    }
}

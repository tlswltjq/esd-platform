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
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** The administrator boundary authenticates the operator; this transaction stores the durable instruction. */
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RequestRefundCancellationResume {
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final RefundCancellationHoldRepository holds;
    private final Clock clock;

    public RefundCancellationResumeReceipt execute(RefundCancellationResumeRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        String paymentId = executions.findPaymentIdByAttemptId(request.attemptId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // Read only the scalar before the payment lock, then load the latest execution under its lock.
        Payment payment = payments.lockByPaymentId(paymentId).orElseThrow();
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
        try {
            executions.flush();
        } catch (DataIntegrityViolationException collision) {
            if (isResumeRequestCollision(collision))
                throw new BusinessException(ErrorCode.CONFLICT, "다른 중단에서 이미 접수한 재개 명령입니다.");
            throw collision;
        }
        return hold.receipt();
    }

    private boolean isResumeRequestCollision(DataIntegrityViolationException collision) {
        Throwable cause = collision;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraint) {
                String name = constraint.getConstraintName();
                if (name != null && (name.equals("uq_cancel_resume_request") || name.endsWith(".uq_cancel_resume_request"))) return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}

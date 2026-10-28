package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundInvestigation;
import com.stove.payment.core.domain.RefundInvestigationRepository;
import com.stove.payment.core.domain.RefundInvestigationRequest;
import com.stove.payment.core.domain.RefundInvestigationView;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RecordRefundInvestigation {
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final RefundInvestigationRepository investigations;
    private final Clock clock;

    public RefundInvestigationView execute(RefundInvestigationRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        String paymentId = executions.findPaymentIdByAttemptId(request.attemptId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Payment payment = payments.lockByPaymentId(paymentId).orElseThrow();
        RefundExecution execution = executions.lockByAttemptId(request.attemptId()).orElseThrow();
        payment.requireRefundRequest(execution.request());
        if (!"PORTONE_TEST".equals(payment.getProvider())) throw new BusinessException(ErrorCode.CONFLICT);
        RefundInvestigation known = investigations.findByRequestId(request.requestId()).orElse(null);
        if (known != null) { known.requireSame(request); return known.view(); }
        RefundInvestigation investigation = RefundInvestigation.record(execution, request, clock.instant());
        try {
            investigations.saveAndFlush(investigation);
        } catch (DataIntegrityViolationException collision) {
            if (isCommandCollision(collision)) throw new BusinessException(ErrorCode.CONFLICT, "다른 시도에서 이미 사용한 조사 기록 명령입니다.");
            throw collision;
        }
        return investigation.view();
    }

    private boolean isCommandCollision(DataIntegrityViolationException collision) {
        Throwable cause = collision;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraint) {
                String name = constraint.getConstraintName();
                if (name != null && (name.equals("uq_refund_investigation_request") || name.endsWith(".uq_refund_investigation_request"))) return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}

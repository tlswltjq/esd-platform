package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.ApprovalInvestigation;
import com.stove.payment.core.domain.ApprovalInvestigationRepository;
import com.stove.payment.core.domain.ApprovalInvestigationRequest;
import com.stove.payment.core.domain.ApprovalInvestigationView;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RecordApprovalInvestigation {
    private final PaymentRepository payments;
    private final PaymentApprovalRepository approvals;
    private final ApprovalInvestigationRepository investigations;
    private final Clock clock;
    public ApprovalInvestigationView execute(ApprovalInvestigationRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        String paymentId = approvals.findPaymentIdByApprovalId(request.approvalId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        payments.lockByPaymentId(paymentId).orElseThrow();
        PaymentApproval approval = approvals.lockByApprovalId(request.approvalId()).orElseThrow();
        if (approval.getStatus() != PaymentApprovalStatus.REVIEW_REQUIRED) throw new BusinessException(ErrorCode.CONFLICT);
        ApprovalInvestigation known = investigations.findByRequestId(request.requestId()).orElse(null);
        if (known != null) { known.requireSame(request); return known.view(); }
        ApprovalInvestigation record = ApprovalInvestigation.record(approval, request, clock.instant());
        try { investigations.saveAndFlush(record); }
        catch (DataIntegrityViolationException collision) {
            Throwable cause = collision;
            while (cause != null) {
                if (cause instanceof ConstraintViolationException constraint) {
                    String name = constraint.getConstraintName();
                    if (name != null && (name.equals("uq_approval_investigation_request") || name.endsWith(".uq_approval_investigation_request")))
                        throw new BusinessException(ErrorCode.CONFLICT);
                }
                cause = cause.getCause();
            }
            throw collision;
        }
        return record.view();
    }
}

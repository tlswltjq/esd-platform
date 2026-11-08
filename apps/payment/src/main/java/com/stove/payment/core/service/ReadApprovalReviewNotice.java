package com.stove.payment.core.service;

import com.stove.payment.core.domain.ApprovalClosure;
import com.stove.payment.core.domain.ApprovalClosureRepository;
import com.stove.payment.core.domain.ApprovalReviewNotice;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the customer notice of a purchase's held or review-closed approval (decision 123). A purchase has at most one
 * unresolved approval and a closed review ends the purchase, so the latest approval is the one the customer sees.
 */
@Transactional(readOnly = true)
public class ReadApprovalReviewNotice {
    private final PaymentApprovalRepository approvals;
    private final ApprovalClosureRepository closures;

    public ReadApprovalReviewNotice(PaymentApprovalRepository approvals, ApprovalClosureRepository closures) {
        this.approvals = approvals; this.closures = closures;
    }

    public Optional<ApprovalReviewNotice> forPayment(Payment payment) {
        return approvals.findFirstByPaymentIdOrderByIdDesc(payment.getPaymentId()).flatMap(this::forApproval);
    }

    public Optional<ApprovalReviewNotice> forApproval(PaymentApproval approval) {
        ApprovalClosure closure = approval.getStatus() == PaymentApprovalStatus.FAILED
                ? closures.findByApprovalId(approval.getApprovalId()).orElse(null) : null;
        return ApprovalReviewNotice.of(approval, closure);
    }
}

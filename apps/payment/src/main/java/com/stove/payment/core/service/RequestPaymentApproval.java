package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.ApprovalPurchase;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalInput;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.port.ApprovalPayloadProtection;
import com.stove.payment.core.port.PgPaymentApprovalGateway;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class RequestPaymentApproval {
    private final PaymentRepository payments;
    private final PaymentApprovalRepository approvals;
    private final PgPaymentApprovalGateway gateway;
    private final ApprovalPayloadProtection protection;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public RequestPaymentApproval(PaymentRepository payments, PaymentApprovalRepository approvals, PgPaymentApprovalGateway gateway,
            ApprovalPayloadProtection protection, Clock clock, PlatformTransactionManager transactions) {
        this.payments=payments; this.approvals=approvals; this.gateway=gateway; this.protection=protection; this.clock=clock;
        transaction=new TransactionTemplate(transactions); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public PaymentApproval execute(String buyer, PaymentApprovalInput input) {
        if (input == null || buyer == null || buyer.isBlank()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Payment snapshot=payments.findByOrderNoAndBuyerSubject(input.orderNo(),buyer)
                .orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
        PaymentApproval known=approvals.findByApprovalId(input.requestId()).orElse(null);
        if (known!=null) { known.requireSameInput(snapshot,input); return known; }
        if (!"PORTONE_TEST".equals(snapshot.getProvider()) || snapshot.getStatus()!=PaymentStatus.PENDING)
            throw new BusinessException(ErrorCode.CONFLICT, "Approval requires a pending PortOne purchase");
        String body=gateway.prepareBody(new ApprovalPurchase(snapshot.chargeRequest(),input.supplierAttemptId()),input.token());
        String encrypted=protection.seal(input.requestId(),body);
        try {
            return transaction.execute(tx->{
                Payment current=payments.lockByPaymentId(snapshot.getPaymentId()).orElseThrow();
                PaymentApproval previous=approvals.findByApprovalId(input.requestId()).orElse(null);
                if (previous!=null) { previous.requireSameInput(current,input); return previous; }
                List<PaymentApprovalStatus> active=List.of(PaymentApprovalStatus.REQUESTED,PaymentApprovalStatus.ADMITTED,
                        PaymentApprovalStatus.REVIEW_REQUIRED,PaymentApprovalStatus.FAILURE_CONFIRMED);
                if (approvals.findFirstByPaymentIdAndStatusInOrderByIdDesc(current.getPaymentId(),active).isPresent()
                        || approvals.findBySupplierAttemptId(input.supplierAttemptId()).isPresent())
                    throw new BusinessException(ErrorCode.CONFLICT,"A payment approval is already active or this attempt was received");
                return approvals.saveAndFlush(PaymentApproval.receive(current,input,encrypted,clock.instant().truncatedTo(ChronoUnit.MICROS)));
            });
        } catch (DataIntegrityViolationException collision) {
            PaymentApproval previous=approvals.findByApprovalId(input.requestId()).orElse(null);
            if (previous!=null) { previous.requireSameInput(snapshot,input); return previous; }
            throw new BusinessException(ErrorCode.CONFLICT,"Approval command identity is already used");
        }
    }
}

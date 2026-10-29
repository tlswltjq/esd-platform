package com.stove.payment.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.OrderExpiryInput;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.port.OrderPaymentLifecycleLocks;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.PaymentOrderLifecycle;
import com.stove.payment.core.domain.PaymentOrderLifecycleRepository;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Transactional(isolation=Isolation.READ_COMMITTED)
public class ApplyOrderExpiry {
    public static final String CONSUMER_GROUP="payment-order-expiry";
    private final PaymentOrderLifecycleRepository lifecycle;
    private final OrderPaymentLifecycleLocks locks;
    private final PaymentRepository payments;
    private final PaymentApprovalRepository approvals;
    private final ProcessedEventGuard inbox;
    public ApplyOrderExpiry(PaymentOrderLifecycleRepository lifecycle,PaymentRepository payments,PaymentApprovalRepository approvals,ProcessedEventGuard inbox,OrderPaymentLifecycleLocks locks) {
        this.lifecycle=lifecycle; this.locks=locks; this.payments=payments; this.approvals=approvals; this.inbox=inbox;
    }
    public void execute(OrderExpiryInput input) {
        if(input==null)throw new BusinessException(ErrorCode.INVALID_REQUEST);
        locks.ensure(input.orderNo(),input.requestEventId(),input.buyerSubject(),input.amount(),input.currency(),input.expiresAt());
        PaymentOrderLifecycle current=lifecycle.lockByOrderNo(input.orderNo()).orElseThrow();
        boolean changed=current.recordExpiry(input);
        boolean first=inbox.firstDelivery(input.eventId(),CONSUMER_GROUP,EventType.ORDER_EXPIRED);
        if(changed!=first)throw new BusinessException(ErrorCode.CONFLICT,"Expiry receipt and Inbox do not agree");
        if(!first)return;
        Payment payment=payments.lockByOrderNo(input.orderNo()).orElse(null);
        if(payment==null)return;
        current.requireOriginal(payment.orderRequest());
        if(payment.getResultEventId()!=null) {
            current.retainFinancialConflict();
            log.warn("Order expiry conflicts with an immutable financial result: orderNo={}",input.orderNo());
            return;
        }
        if(payment.getStatus()!=PaymentStatus.PENDING)return;
        List<PaymentApprovalStatus> active=List.of(PaymentApprovalStatus.REQUESTED,PaymentApprovalStatus.ADMITTED,
                PaymentApprovalStatus.REVIEW_REQUIRED,PaymentApprovalStatus.FAILURE_CONFIRMED);
        // A worker may hold a lost admission/closure response. Keep it until that existing flow verifies its result.
        if(approvals.findFirstByPaymentIdAndStatusInOrderByIdDesc(payment.getPaymentId(),active).isEmpty())payment.expireUnpaid();
    }
}

package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly=true)
public class ReadPaymentApproval {
    private final ReadPayment payments;
    private final PaymentApprovalRepository approvals;
    public ReadPaymentApproval(ReadPayment payments,PaymentApprovalRepository approvals) { this.payments=payments; this.approvals=approvals; }
    public PaymentApproval execute(String buyer,String orderNo,String requestId) {
        Payment payment=payments.execute(orderNo,buyer);
        return approvals.findByApprovalId(requestId).filter(value->value.getPaymentId().equals(payment.getPaymentId()))
                .orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
    }
}

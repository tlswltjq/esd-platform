package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.FailedPaymentAttemptRepository;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentFailureHistory;
import com.stove.payment.core.domain.PaymentFailureHistoryQuery;
import com.stove.payment.core.domain.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadPaymentFailureHistory {
    private final PaymentRepository payments;
    private final FailedPaymentAttemptRepository attempts;

    public PaymentFailureHistory execute(PaymentFailureHistoryQuery query) {
        if (query == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Payment payment = payments.findByOrderNoAndBuyerSubject(query.orderNo(), query.buyerSubject()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return new PaymentFailureHistory(attempts.findByPaymentId(payment.getPaymentId(), PageRequest.of(query.page(), query.size(),
                Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("failedAt"), Sort.Order.desc("id")))),
                payment.getFailureHistoryCheckedAt(), payment.getNextFailureLookupAt() != null);
    }
}

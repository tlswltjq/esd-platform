package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundView;
import com.stove.order.core.domain.RefundStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadRefund {
    private final OrderRepository orders;
    private final RefundRepository refunds;

    public RefundView execute(String orderNo, String buyer) {
        Order order = orders.findByOrderNoAndBuyerSubject(orderNo, buyer)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Refund refund = refunds.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return new RefundView(refund.getRefundId(), orderNo, order.getAmount(), order.getCurrency(),
                refund.getStatus(), refund.getReceivedAt(), refund.getCompletedAt(),
                refund.getStatus() == RefundStatus.REJECTED ? refund.getReviewReason() : null);
    }
}

package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReviewRound;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import com.stove.order.core.domain.RefundReviewRoundView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(readOnly = true)
public class ReadRefundReviewRounds {
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundReviewRoundRepository rounds;

    public Page<RefundReviewRoundView> customer(String orderNo, String buyer, int page, int size) {
        validatePage(page, size);
        Order order = orders.findByOrderNoAndBuyerSubject(orderNo, buyer).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Refund refund = refunds.findByOrderNo(order.getOrderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return history(refund.getRefundId(), page, size);
    }
    public Page<RefundReviewRoundView> admin(String refundId, int page, int size) {
        validatePage(page, size);
        Refund refund = refunds.findByRefundId(refundId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return history(refund.getRefundId(), page, size);
    }
    private Page<RefundReviewRoundView> history(String refundId, int page, int size) {
        return rounds.findByRefundIdOrderByReviewNoAsc(refundId, PageRequest.of(page, size)).map(RefundReviewRound::view);
    }
    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}

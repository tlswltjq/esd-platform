package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReviewView;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadRefundReviews {
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundReviewRoundRepository rounds;

    public Page<RefundReviewView> search(RefundStatus status, int page, int size) {
        if (status == null || page < 0 || size < 1 || size > 100)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return refunds.findByStatus(status, PageRequest.of(page, size, Sort.by("receivedAt", "id"))).map(this::view);
    }

    public RefundReviewView read(String refundId) {
        return view(refunds.findByRefundId(refundId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }

    private RefundReviewView view(Refund refund) {
        Order order = orders.findByOrderNo(refund.getOrderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return new RefundReviewView(refund.getRefundId(), order.getOrderNo(), order.getBuyerSubject(), order.getProductCode(),
                order.getTitle(), order.getAmount(), order.getCurrency(), order.getResultOccurredAt(), refund.getReceivedAt(),
                refund.getStatus(), refund.reviewReceipt(), refund.getCurrentReviewId() == null ? null
                        : rounds.findByReviewId(refund.getCurrentReviewId()).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT)).view());
    }
}

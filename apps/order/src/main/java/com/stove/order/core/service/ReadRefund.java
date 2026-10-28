package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundView;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import java.time.Instant;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.domain.RefundReviewRound;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadRefund {
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundReviewRoundRepository rounds;
    private final Clock clock;
    private final RefundAttemptRepository attempts;

    public RefundView execute(String orderNo, String buyer) {
        Order order = orders.findByOrderNoAndBuyerSubject(orderNo, buyer)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Refund refund = refunds.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        RefundReviewRound current = refund.getCurrentReviewId() == null ? null : rounds.findByReviewId(refund.getCurrentReviewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "현재 심사 이력이 없습니다."));
        return new RefundView(refund.getRefundId(), orderNo, order.getAmount(), order.getCurrency(),
                refund.getStatus(), refund.getReceivedAt(), refund.getCompletedAt(),
                refund.getStatus() == RefundStatus.REJECTED ? current == null ? refund.getReviewReason() : current.getDecisionReason() : null,
                refund.getStatus() == RefundStatus.FAILED ? refund.getCurrentAttemptId() : null,
                current == null ? null : current.view(), current == null ? null : current.reconsiderationDeadline(),
                refund.getStatus() == RefundStatus.REJECTED && current != null && current.canReconsiderAt(clock.instant()), delayedSince(refund));
    }
    private Instant delayedSince(Refund refund) {
        if (refund.getStatus()!=RefundStatus.PENDING_PAYMENT_REFUND) return null;
        if (refund.getCurrentAttemptId()==null) return null;
        RefundAttempt attempt=attempts.findByAttemptId(refund.getCurrentAttemptId()).orElse(null);
        if (attempt==null) return null;
        if (!attempt.getRefundId().equals(refund.getRefundId())) throw new BusinessException(ErrorCode.CONFLICT);
        return attempt.confirmationDelayedSince(clock.instant());
    }

}

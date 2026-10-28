package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReconsiderationRequest;
import com.stove.order.core.domain.RefundReconsiderationReceipt;
import com.stove.order.core.domain.RefundReviewRound;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(isolation = Isolation.READ_COMMITTED)
public class RequestRefundReconsideration {
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundReviewRoundRepository rounds;
    private final Clock clock;

    public RefundReconsiderationReceipt execute(RefundReconsiderationRequest input) {
        if (input == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Instant receivedAt = clock.instant();
        Order order = orders.lockByOrderNo(input.orderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!order.getBuyerSubject().equals(input.buyerSubject())) throw new BusinessException(ErrorCode.NOT_FOUND);
        Refund refund = refunds.findByOrderNo(input.orderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (order.getStatus() != OrderStatus.PAID) throw new BusinessException(ErrorCode.CONFLICT);
        RefundReviewRound known = rounds.findByApplicationRequestId(input.requestId()).orElse(null);
        if (known != null) { known.requireSameApplication(refund, input); return known.applicationReceipt(); }
        RefundReviewRound previous = rounds.findByReviewId(input.previousReviewId()).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        if (rounds.existsByPreviousReviewId(input.previousReviewId())) throw new BusinessException(ErrorCode.CONFLICT, "이미 재심사를 접수한 반려 회차입니다.");
        RefundReviewRound next = RefundReviewRound.reconsider(refund, previous, input, receivedAt);
        refund.openReconsideration(previous, next);
        try { rounds.saveAndFlush(next); }
        catch (DataIntegrityViolationException collision) {
            Throwable cause = collision;
            while (cause != null) {
                if (cause instanceof ConstraintViolationException constraint) {
                    String name = constraint.getConstraintName();
                    if (name != null && (name.endsWith("uk_refund_review_application") || name.endsWith("uk_refund_review_previous")))
                        throw new BusinessException(ErrorCode.CONFLICT, "이미 접수한 재심사 명령 또는 반려 회차와 충돌합니다.");
                }
                cause = cause.getCause();
            }
            throw collision;
        }
        return next.applicationReceipt();
    }
}

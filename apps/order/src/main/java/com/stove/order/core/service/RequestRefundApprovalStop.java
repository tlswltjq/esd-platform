package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReviewRound;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import com.stove.order.core.domain.RefundApprovalStopRequest;
import com.stove.order.core.domain.RefundApprovalStopReceipt;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(isolation=Isolation.READ_COMMITTED)
public class RequestRefundApprovalStop {
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundReviewRoundRepository rounds;
    private final Clock clock;

    public RefundApprovalStopReceipt execute(RefundApprovalStopRequest input) {
        if (input==null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Instant at=clock.instant();
        String orderNo=refunds.findOrderNoByRefundId(input.refundId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Order order=orders.lockByOrderNo(orderNo).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Refund refund=refunds.findByRefundId(input.refundId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        RefundReviewRound known=rounds.findByApprovalStop_RequestId(input.requestId()).orElse(null);
        if (known!=null) { known.getApprovalStop().requireSame(input,known); return known.getApprovalStop().receipt(known.getRefundId(),known.getReviewId()); }
        RefundReviewRound round=rounds.findByReviewId(input.reviewId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (order.getStatus()!=OrderStatus.PAID) throw new BusinessException(ErrorCode.CONFLICT);
        refund.stopApproval(round,input,at);
        try {
            if (round.getApprovalStop().canReopen()) {
                RefundReviewRound next=RefundReviewRound.correctApproval(round,at);
                refund.reopenStoppedApproval(round,next,at); rounds.save(next);
            }
            rounds.flush();
        } catch (DataIntegrityViolationException collision) {
            Throwable cause=collision;
            while (cause!=null) {
                if (cause instanceof ConstraintViolationException constraint && isStopCommandCollision(constraint.getConstraintName()))
                    throw new BusinessException(ErrorCode.CONFLICT,"이미 사용된 중단 명령입니다.");
                cause=cause.getCause();
            }
            throw collision;
        }
        return round.getApprovalStop().receipt(refund.getRefundId(),round.getReviewId());
    }
    private static boolean isStopCommandCollision(String name) {
        if (name==null) return false;
        String key=name.substring(name.lastIndexOf('.')+1);
        return key.equals("uk_review_stop_request") || key.equals("uk_review_correction_request");
    }
}

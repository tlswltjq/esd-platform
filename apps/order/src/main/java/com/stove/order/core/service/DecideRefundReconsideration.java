package com.stove.order.core.service;

import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.OrderLicenseGrantRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundRoundDecisionRequest;
import com.stove.order.core.domain.RefundReviewRequest;
import com.stove.order.core.domain.RefundReviewDecision;
import com.stove.order.core.domain.RefundReviewRound;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import com.stove.order.core.domain.RefundReviewRoundView;
import com.stove.order.core.domain.RefundStatus;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(isolation = Isolation.READ_COMMITTED)
public class DecideRefundReconsideration {
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundReviewRoundRepository rounds;
    private final OrderLicenseGrantRepository grants;
    private final OutboxRecorder outbox;
    private final Clock clock;

    public RefundReviewRoundView execute(RefundRoundDecisionRequest input) {
        if (input == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        RefundReviewRequest decision = input.decision();
        String orderNo = refunds.findOrderNoByRefundId(decision.refundId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Order order = orders.lockByOrderNo(orderNo).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Refund refund = refunds.findByRefundId(decision.refundId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (order.getStatus() != OrderStatus.PAID) throw new BusinessException(ErrorCode.CONFLICT);
        RefundReviewRound round = rounds.findByReviewId(input.reviewId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        RefundReviewRound used = rounds.findByDecisionRequestId(decision.requestId()).orElse(null);
        if (used != null && !used.getReviewId().equals(round.getReviewId())) throw new BusinessException(ErrorCode.CONFLICT);
        Instant at = clock.instant();
        try {
            if (refund.decideReconsideration(round, decision, at) && decision.decision() == RefundReviewDecision.APPROVED) {
                grants.findByOrderNo(orderNo).ifPresent(grant -> { order.verifyLicenseGrant(grant.confirmation()); refund.confirmLicenseGrant(grant.confirmation()); });
                if (refund.getStatus() == RefundStatus.PENDING_LICENSE_SUSPENSION)
                    outbox.record("Order", orderNo, new LicenseSuspensionRequestedEvent(refund.getSuspensionRequestEventId(), at,
                            refund.getRefundId(), orderNo, order.getBuyerSubject(), order.getProductId(), order.getProductCode(), order.getPaymentId()));
            }
            rounds.flush();
        } catch (DataIntegrityViolationException collision) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 사용한 판정 명령과 충돌합니다.");
        }
        return round.view();
    }
}

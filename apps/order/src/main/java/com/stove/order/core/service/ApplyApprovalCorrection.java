package com.stove.order.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectionRequestedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.order.core.domain.ApprovalCorrectionConfirmation;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundApprovalStop;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundReviewRound;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import com.stove.order.core.domain.RefundStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(isolation=Isolation.READ_COMMITTED)
public class ApplyApprovalCorrection {
    public static final String CONSUMER_GROUP="order-approval-correction";
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundReviewRoundRepository rounds;
    private final ProcessedEventGuard inbox;
    private final OutboxRecorder outbox;
    private final EntityManager entityManager;
    private final Clock clock;

    // Called inside the suspension service's order lock and transaction, after purchase/grant validation.
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean handleStoppedSuspension(Order order, Refund refund, LicenseSuspensionConfirmation input) {
        RefundReviewRound round=rounds.findByApprovalStop_SuspensionRequestId(input.requestEventId()).orElse(null);
        if (round==null) return false;
        if (!round.getRefundId().equals(refund.getRefundId()) || !input.refundId().equals(refund.getRefundId()))
            throw new RefundCompletionRejected("중단한 환불의 정지 근거가 다릅니다.");
        RefundApprovalStop stop=round.getApprovalStop();
        if (stop.getSuspensionEventId()==null && (refund.getStatus()!=RefundStatus.PENDING_APPROVAL_STOP || !round.getReviewId().equals(refund.getCurrentReviewId())))
            throw new RefundCompletionRejected("현재 승인 중단의 정지 결과가 아닙니다.");
        boolean changed=stop.observeSuspension(input,clock.instant());
        boolean first=inbox.firstDelivery(input.eventId(),ApplyRefundSuspension.CONSUMER_GROUP,EventType.LICENSE_SUSPENDED);
        if (changed!=first) throw new RefundCompletionRejected("정정 정지 근거와 Inbox가 다릅니다.");
        if (changed) outbox.record("Order",order.getOrderNo(),new LicenseApprovalCorrectionRequestedEvent(stop.getRestorationRequestId(),
                stop.getRestorationRequestedAt(),refund.getRefundId(),round.getReviewId(),stop.getRequestId(),input.licenseId(),order.getOrderNo(),
                order.getBuyerSubject(),order.getProductId(),order.getProductCode(),order.getPaymentId(),input.requestEventId(),input.eventId()));
        entityManager.flush(); return true;
    }
    public void execute(ApprovalCorrectionConfirmation input) {
        if (input==null) throw new RefundCompletionRejected("승인 정정 복구 근거가 필요합니다.");
        Order order=orders.lockByOrderNo(input.orderNo()).orElseThrow(() -> new RefundCompletionRejected("원래 주문이 없습니다."));
        Refund refund=refunds.findByOrderNo(input.orderNo()).orElseThrow(() -> new RefundCompletionRejected("환불이 없습니다."));
        RefundReviewRound round=rounds.findByReviewId(input.reviewId()).orElseThrow(() -> new RefundCompletionRejected("심사 회차가 없습니다."));
        if (!round.getRefundId().equals(refund.getRefundId()) || !input.refundId().equals(refund.getRefundId())
                || !order.getBuyerSubject().equals(input.buyerSubject()) || !order.getProductId().equals(input.productId())
                || !order.getProductCode().equals(input.productCode()) || !order.getPaymentId().equals(input.paymentId()) || round.getApprovalStop()==null)
            throw new RefundCompletionRejected("복구 완료의 구매·심사 근거가 다릅니다.");
        boolean changed=round.getApprovalStop().observeRestored(input);
        boolean first=inbox.firstDelivery(input.eventId(),CONSUMER_GROUP,EventType.LICENSE_APPROVAL_CORRECTED);
        if (changed!=first) throw new RefundCompletionRejected("복구 근거와 Inbox가 다릅니다.");
        if (changed) {
            Instant at=clock.instant(); RefundReviewRound next=RefundReviewRound.correctApproval(round,at);
            refund.reopenStoppedApproval(round,next,at); rounds.save(next);
        }
        entityManager.flush();
    }
}

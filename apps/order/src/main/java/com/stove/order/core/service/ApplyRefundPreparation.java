package com.stove.order.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundPreparationConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(isolation=Isolation.READ_COMMITTED)
public class ApplyRefundPreparation {
    public static final String CONSUMER_GROUP="order-refund-preparation";
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundAttemptRepository attempts;
    private final ProcessedEventGuard inbox;
    public void execute(RefundPreparationConfirmation input) {
        if (input==null) throw new RefundCompletionRejected("환불 준비 근거가 필요합니다.");
        Order order=orders.lockByOrderNo(input.orderNo()).orElseThrow(()->new RefundCompletionRejected("준비 사실의 원래 주문이 없습니다."));
        Refund refund=refunds.findByOrderNo(input.orderNo()).orElseThrow(()->new RefundCompletionRejected("준비 사실의 환불이 없습니다."));
        RefundAttempt attempt=attempts.findByAttemptId(input.attemptId()).orElseThrow(()->new RefundCompletionRejected("준비 사실의 실행 요청이 없습니다."));
        RefundAttempt known=attempts.findByPreparationEventId(input.eventId()).orElse(null);
        if (!refund.getRefundId().equals(input.refundId()) || (known!=null && !known.getAttemptId().equals(attempt.getAttemptId())))
            throw new RefundCompletionRejected("다른 환불이나 실행에 사용된 준비 근거입니다.");
        boolean changed=attempt.recordPreparation(order,input);
        boolean first=inbox.firstDelivery(input.eventId(),CONSUMER_GROUP,EventType.REFUND_EXECUTION_PREPARED);
        if (changed!=first) throw new RefundCompletionRejected("준비 이력과 Inbox가 다릅니다.");
        attempts.flush();
    }
}

package com.stove.order.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import com.stove.order.core.domain.RefundExecutionSuccess;
import com.stove.order.core.domain.RefundRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ApplyRefundPaymentConfirmation {
    public static final String CONSUMER_GROUP = "order-refund-payment-confirmation";
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundAttemptRepository attempts;
    private final ProcessedEventGuard inbox;
    private final EntityManager entityManager;

    public void execute(RefundExecutionSuccess input) {
        if (input == null) throw new RefundCompletionRejected("실행 성공 근거가 필요합니다.");
        RefundPaymentConfirmation proof = input.execution();
        Order order = orders.lockByOrderNo(proof.orderNo()).orElseThrow(() -> new RefundCompletionRejected("원래 주문이 없습니다."));
        Refund refund = refunds.findByOrderNo(proof.orderNo()).orElseThrow(() -> new RefundCompletionRejected("환불 접수가 없습니다."));
        RefundAttempt attempt = attempts.findByAttemptId(proof.attemptId()).orElseThrow(() -> new RefundCompletionRejected("실행 접수가 없습니다."));
        boolean changed = refund.recordExecutionSuccess(order, attempt, input);
        boolean first = inbox.firstDelivery(proof.eventId(), CONSUMER_GROUP, EventType.REFUND_EXECUTION_SUCCEEDED);
        if (changed != first) throw new RefundCompletionRejected("실행 성공 기록과 Inbox가 다릅니다.");
        entityManager.flush();
    }

    public void execute(RefundPaymentConfirmation input) {
        if (input == null) throw new RefundCompletionRejected("전액 취소 확인이 필요합니다.");
        Order order = orders.lockByOrderNo(input.orderNo()).orElseThrow(() -> new RefundCompletionRejected("원래 주문이 없습니다."));
        Refund refund = refunds.findByOrderNo(input.orderNo()).orElseThrow(() -> new RefundCompletionRejected("환불 접수가 없습니다."));
        RefundAttempt attempt = attempts.findByAttemptId(input.attemptId()).orElseThrow(() -> new RefundCompletionRejected("환불 실행이 없습니다."));
        boolean changed = refund.confirmPayment(order, attempt, input);
        boolean first = inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.REFUND_PAYMENT_CONFIRMED);
        if (changed != first) throw new RefundCompletionRejected("환불 완료 기록과 Inbox가 다릅니다.");
        entityManager.flush();
    }
}

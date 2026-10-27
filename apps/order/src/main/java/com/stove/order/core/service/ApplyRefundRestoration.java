package com.stove.order.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.LicenseRestorationRequestedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.LicenseRestorationConfirmation;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(isolation = Isolation.READ_COMMITTED)
public class ApplyRefundRestoration {
    public static final String CONSUMER_GROUP = "order-refund-restoration";
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundAttemptRepository attempts;
    private final ProcessedEventGuard inbox;
    private final OutboxRecorder outbox;
    private final EntityManager entityManager;
    private final Clock clock;

    public void execute(RefundFailureConfirmation input) {
        if (input == null) throw new RefundCompletionRejected("실패 근거가 필요합니다.");
        Order order = orders.lockByOrderNo(input.orderNo()).orElseThrow(() -> new RefundCompletionRejected("원래 주문이 없습니다."));
        Refund refund = refunds.findByOrderNo(input.orderNo()).orElseThrow(() -> new RefundCompletionRejected("환불 접수가 없습니다."));
        RefundAttempt attempt = attempts.findByAttemptId(input.attemptId()).orElseThrow(() -> new RefundCompletionRejected("실행 접수가 없습니다."));
        boolean changed = refund.recordFailure(order, attempt, input, clock.instant());
        boolean first = inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.REFUND_EXECUTION_FAILED);
        if (changed != first) throw new RefundCompletionRejected("실패 기록과 Inbox가 다릅니다.");
        if (changed) outbox.record("Order", order.getOrderNo(), new LicenseRestorationRequestedEvent(attempt.getRestorationRequestEventId(),
                attempt.getRestorationRequestedAt(), refund.getRefundId(), attempt.getAttemptId(), attempt.getFailureEventId(), attempt.getLicenseId(),
                order.getOrderNo(), order.getBuyerSubject(), order.getProductId(), order.getProductCode(), order.getPaymentId(),
                attempt.getSuspensionRequestEventId(), attempt.getSuspensionEventId()));
        entityManager.flush();
    }

    public void execute(LicenseRestorationConfirmation input) {
        if (input == null) throw new RefundCompletionRejected("복구 완료가 필요합니다.");
        Order order = orders.lockByOrderNo(input.orderNo()).orElseThrow(() -> new RefundCompletionRejected("원래 주문이 없습니다."));
        Refund refund = refunds.findByOrderNo(input.orderNo()).orElseThrow(() -> new RefundCompletionRejected("환불 접수가 없습니다."));
        RefundAttempt attempt = attempts.findByAttemptId(input.attemptId()).orElseThrow(() -> new RefundCompletionRejected("실행 접수가 없습니다."));
        boolean changed = refund.confirmRestoration(order, attempt, input);
        boolean first = inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.LICENSE_RESTORED);
        if (changed != first) throw new RefundCompletionRejected("복구 완료 기록과 Inbox가 다릅니다.");
        entityManager.flush();
    }
}

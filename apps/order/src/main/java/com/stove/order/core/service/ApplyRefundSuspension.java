package com.stove.order.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.RefundExecutionRequestedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderLicenseGrant;
import com.stove.order.core.domain.OrderLicenseGrantRepository;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ApplyRefundSuspension {
    public static final String CONSUMER_GROUP = "order-refund-suspension";
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final OrderLicenseGrantRepository grants;
    private final RefundAttemptRepository attempts;
    private final ProcessedEventGuard inbox;
    private final OutboxRecorder outbox;
    private final EntityManager entityManager;
    private final Clock clock;

    public void execute(LicenseSuspensionConfirmation input) {
        if (input == null) throw new RefundCompletionRejected("정지 완료 근거가 필요합니다.");
        Order order = orders.lockByOrderNo(input.orderNo())
                .orElseThrow(() -> new RefundCompletionRejected("정지 완료의 원래 주문이 없습니다."));
        Refund refund = refunds.findByOrderNo(input.orderNo())
                .orElseThrow(() -> new RefundCompletionRejected("정지 완료의 환불 접수 기록이 없습니다."));
        OrderLicenseGrant grant = grants.findByOrderNo(input.orderNo())
                .orElseThrow(() -> new RefundCompletionRejected("정지 완료의 지급 확인 근거가 없습니다."));
        try {
            order.verifyLicenseGrant(grant.confirmation());
        } catch (BusinessException inconsistent) {
            throw new RefundCompletionRejected("지급 확인이 원래 결제 주문과 다릅니다.", inconsistent);
        }
        grant.verifySuspension(input);
        RefundAttempt known = attempts.findBySuspensionEventId(input.eventId()).orElse(null);
        if (known != null) {
            if (!known.getRefundId().equals(refund.getRefundId())) throw new RefundCompletionRejected("다른 환불의 정지 완료입니다.");
            known.verifySuspension(input);
            if (inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.LICENSE_SUSPENDED))
                throw new RefundCompletionRejected("실행 기록에 해당하는 Inbox가 없습니다.");
            return;
        }
        refund.verifySuspensionRequest(input);
        if (refund.getStatus() != RefundStatus.PENDING_LICENSE_SUSPENSION)
            throw new RefundCompletionRejected("실제 정지 완료를 기다리는 환불이 아닙니다.");
        RefundAttempt previous = refund.getCurrentAttemptId() == null ? null : attempts.findByAttemptId(refund.getCurrentAttemptId())
                .orElseThrow(() -> new RefundCompletionRejected("재실행 이전 시도가 없습니다."));
        if (previous == null && attempts.existsByRefundId(refund.getRefundId()))
            throw new RefundCompletionRejected("현재 실행 없이 과거 시도가 존재합니다.");
        if (!inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.LICENSE_SUSPENDED)) {
            throw new RefundCompletionRejected("Inbox에 대응하는 실행 기록이 없습니다.");
        }
        RefundAttempt attempt = previous == null ? RefundAttempt.afterSuspension(refund, input, clock.instant())
                : RefundAttempt.afterRetrySuspension(refund, previous, input, clock.instant());
        refund.recordExecution(attempt);
        attempts.save(attempt);
        RefundExecutionRequestedEvent event = new RefundExecutionRequestedEvent(attempt.getExecutionRequestEventId(),
                attempt.getStartedAt(), refund.getRefundId(), attempt.getAttemptId(), order.getOrderNo(),
                order.getPaymentId(), order.getBuyerSubject(), order.getAmount(), order.getCurrency(), input.eventId(), attempt.getPreviousAttemptId());
        outbox.record("Order", order.getOrderNo(), event);
        entityManager.flush();
    }
}

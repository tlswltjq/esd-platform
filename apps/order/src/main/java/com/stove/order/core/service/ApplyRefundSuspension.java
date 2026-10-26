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
        refund.verifySuspensionRequest(input);
        if (refund.getCurrentAttemptId() != null) {
            if (refund.getStatus() != RefundStatus.PENDING_PAYMENT_REFUND) {
                throw new RefundCompletionRejected("현재 환불 단계와 실행 기록이 다릅니다.");
            }
            RefundAttempt previous = attempts.findByAttemptId(refund.getCurrentAttemptId())
                    .orElseThrow(() -> new RefundCompletionRejected("현재 실행의 처리 근거가 없습니다."));
            previous.verifySuspension(input);
            if (previous.getAttemptNo() != 1) throw new RefundCompletionRejected("첫 실행의 순번이 다릅니다.");
            if (inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.LICENSE_SUSPENDED)) {
                throw new RefundCompletionRejected("실행 기록에 해당하는 Inbox가 없습니다.");
            }
            return;
        }
        if (refund.getStatus() != RefundStatus.PENDING_LICENSE_SUSPENSION
                || attempts.existsByRefundId(refund.getRefundId())
                || attempts.findBySuspensionEventId(input.eventId()).isPresent()) {
            throw new RefundCompletionRejected("첫 실행을 시작할 수 없거나 완료 식별자가 이미 사용됐습니다.");
        }
        if (!inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.LICENSE_SUSPENDED)) {
            throw new RefundCompletionRejected("Inbox에 대응하는 실행 기록이 없습니다.");
        }
        RefundAttempt attempt = RefundAttempt.afterSuspension(refund, input, clock.instant());
        refund.recordExecution(attempt);
        attempts.save(attempt);
        RefundExecutionRequestedEvent event = new RefundExecutionRequestedEvent(attempt.getExecutionRequestEventId(),
                attempt.getStartedAt(), refund.getRefundId(), attempt.getAttemptId(), order.getOrderNo(),
                order.getPaymentId(), order.getBuyerSubject(), order.getAmount(), order.getCurrency(), input.eventId());
        outbox.record("Order", order.getOrderNo(), event);
        entityManager.flush();
    }
}

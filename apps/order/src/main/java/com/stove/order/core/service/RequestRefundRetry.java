package com.stove.order.core.service;

import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundRetryRequest;
import com.stove.order.core.domain.RefundRetryReceipt;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(isolation = Isolation.READ_COMMITTED)
public class RequestRefundRetry {
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundAttemptRepository attempts;
    private final OutboxRecorder outbox;
    private final EntityManager entityManager;
    private final Clock clock;

    public RefundRetryReceipt execute(RefundRetryRequest input) {
        if (input == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Order order = orders.lockByOrderNo(input.orderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!order.getBuyerSubject().equals(input.buyerSubject())) throw new BusinessException(ErrorCode.NOT_FOUND);
        Refund refund = refunds.findByOrderNo(input.orderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        RefundAttempt previous = attempts.findByAttemptId(input.previousAttemptId()).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        if (refund.requestRetry(order, previous, clock.instant())) {
            outbox.record("Order", order.getOrderNo(), new LicenseSuspensionRequestedEvent(previous.getRetrySuspensionRequestEventId(),
                    previous.getRetryRequestedAt(), refund.getRefundId(), order.getOrderNo(), order.getBuyerSubject(),
                    order.getProductId(), order.getProductCode(), order.getPaymentId()));
        }
        entityManager.flush();
        return new RefundRetryReceipt(refund.getRefundId(), previous.getAttemptId(), previous.getRetrySuspensionRequestEventId(), previous.getRetryRequestedAt());
    }
}

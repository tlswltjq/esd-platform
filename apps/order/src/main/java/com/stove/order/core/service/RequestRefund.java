package com.stove.order.core.service;

import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderLicenseGrantRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.domain.RefundReviewRound;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RequestRefund {
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final OrderLicenseGrantRepository grants;
    private final OutboxRecorder outbox;
    private final Clock clock;
    private final RefundReviewRoundRepository rounds;

    public Refund execute(String buyerSubject, String orderNo) {
        requireBuyer(buyerSubject);
        requireOrderNo(orderNo);
        Instant receivedAt = clock.instant();
        Order order = orders.lockByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!order.getBuyerSubject().equals(buyerSubject)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        Optional<Refund> previous = refunds.findByOrderNo(orderNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        Refund refund = Refund.request(order, receivedAt);
        grants.findByOrderNo(orderNo).ifPresent(grant -> {
            order.verifyLicenseGrant(grant.confirmation());
            refund.confirmLicenseGrant(grant.confirmation());
        });
        refunds.save(refund);
        if (!refund.isAutomaticRefundEligible()) rounds.save(RefundReviewRound.initial(refund, buyerSubject));
        if (refund.getStatus() == RefundStatus.PENDING_LICENSE_SUSPENSION) {
            LicenseSuspensionRequestedEvent event = new LicenseSuspensionRequestedEvent(
                    refund.getSuspensionRequestEventId(), receivedAt, refund.getRefundId(), orderNo,
                    order.getBuyerSubject(), order.getProductId(), order.getProductCode(), order.getPaymentId());
            outbox.record("Order", orderNo, event);
        }
        return refund;
    }

    private static void requireBuyer(String buyerSubject) {
        if (buyerSubject == null || buyerSubject.isBlank() || buyerSubject.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "구매자 식별자가 올바르지 않습니다.");
        }
    }

    private static void requireOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank() || orderNo.length() > 36) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "주문번호가 올바르지 않습니다.");
        }
    }
}

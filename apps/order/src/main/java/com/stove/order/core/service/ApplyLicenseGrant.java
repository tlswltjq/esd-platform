package com.stove.order.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderLicenseGrant;
import com.stove.order.core.domain.OrderLicenseGrantRepository;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.RefundRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ApplyLicenseGrant {
    public static final String CONSUMER_GROUP = "order-license-grant";
    private final OrderRepository orders;
    private final OrderLicenseGrantRepository grants;
    private final RefundRepository refunds;
    private final ProcessedEventGuard inbox;
    private final OutboxRecorder outbox;
    private final EntityManager entityManager;
    private final Clock clock;

    public void execute(LicenseGrant input) {
        if (input == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지급 확인 근거가 필요합니다.");
        }
        Order order = orders.lockByOrderNo(input.orderNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        order.verifyLicenseGrant(input);
        Optional<OrderLicenseGrant> previous = grants.findByOrderNo(input.orderNo());
        if (previous.isPresent()) {
            previous.get().verify(input);
            return;
        }
        if (grants.findByEventId(input.eventId()).isPresent() || grants.findByLicenseId(input.licenseId()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "다른 주문에 사용된 지급 식별자입니다.");
        }
        if (!inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.LICENSE_GRANTED)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Inbox와 지급 확인 근거가 일치하지 않습니다.");
        }
        grants.save(OrderLicenseGrant.record(input));
        refunds.findByOrderNo(order.getOrderNo()).ifPresent(refund -> {
            if (refund.confirmLicenseGrant(input)) {
                LicenseSuspensionRequestedEvent event = new LicenseSuspensionRequestedEvent(
                        refund.getSuspensionRequestEventId(), clock.instant(), refund.getRefundId(), order.getOrderNo(),
                        order.getBuyerSubject(), order.getProductId(), order.getProductCode(), order.getPaymentId());
                outbox.record("Order", order.getOrderNo(), event);
            }
        });
        entityManager.flush();
    }
}

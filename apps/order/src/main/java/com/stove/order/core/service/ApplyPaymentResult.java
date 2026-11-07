package com.stove.order.core.service;

import com.stove.common.kafka.contract.payload.OrderPaidEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.ExpiredReviewClosure;
import com.stove.order.core.domain.ExpiredReviewClosureRepository;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ApplyPaymentResult {
    public static final String CONSUMER_GROUP = "order-payment-result";
    private final OrderRepository orders;
    private final ProcessedEventGuard guard;
    private final EntityManager entityManager;
    private final OutboxRecorder outbox;
    private final ExpiredReviewClosureRepository keptClosures;

    public void execute(String eventType, PaymentResult result) {
        if (eventType == null || eventType.isBlank() || eventType.length() > 60 || result == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 결과 입력이 올바르지 않습니다.");
        Order order = orders.lockByOrderNo(result.orderNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제 결과의 주문이 없습니다."));
        orders.findByPaymentResultEventId(result.eventId()).ifPresent(previous -> {
            if (!previous.getOrderNo().equals(order.getOrderNo()))
                throw new BusinessException(ErrorCode.CONFLICT, "결과 이벤트 ID가 다른 주문에 사용되었습니다.");
        });
        keptClosures.findByEventId(result.eventId()).ifPresent(previous -> {
            if (!previous.getOrderNo().equals(order.getOrderNo()))
                throw new BusinessException(ErrorCode.CONFLICT, "결과 이벤트 ID가 다른 주문에 사용되었습니다.");
        });
        if (order.paymentResult() != null) { order.apply(result); return; }
        if (order.keepsExpiryFor(result)) { keepExpiry(eventType, order, result); return; }
        if (!guard.firstDelivery(result.eventId(), CONSUMER_GROUP, eventType))
            throw new BusinessException(ErrorCode.CONFLICT, "Inbox와 주문의 결제 결과가 일치하지 않습니다.");
        order.apply(result);
        if (order.getStatus() == OrderStatus.PAID) {
            OrderPaidEvent event = new OrderPaidEvent(UUID.randomUUID().toString(), order.getUpdatedAt(),
                    order.getOrderNo(), order.getRequestEventId(), order.getPaymentResultEventId(),
                    order.getPaymentId(), order.getBuyerSubject(), order.getProductId(), order.getProductCode(),
                    order.getTitle(), order.getContentVersion(), order.getAmount(), order.getCurrency());
            outbox.record("Order", order.getOrderNo(), event);
        }
        entityManager.flush();
    }

    /**
     * 결정 115: 만료와 엇갈린 운영자 종결은 주문을 EXPIRED로 둔 채 결과 식별만 남긴다. 같은 결과의 재전달은 무효과이고,
     * 바뀐 재전달과 기록 없는 Inbox 중복은 다른 결과 충돌처럼 재시도·DLT로 간다.
     */
    private void keepExpiry(String eventType, Order order, PaymentResult result) {
        ExpiredReviewClosure recorded = keptClosures.findByOrderNo(order.getOrderNo()).orElse(null);
        if (recorded != null) { recorded.requireSame(order, result); return; }
        if (!guard.firstDelivery(result.eventId(), CONSUMER_GROUP, eventType))
            throw new BusinessException(ErrorCode.CONFLICT, "Inbox와 주문의 결제 결과가 일치하지 않습니다.");
        keptClosures.save(ExpiredReviewClosure.record(order, result, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        entityManager.flush();
    }
}

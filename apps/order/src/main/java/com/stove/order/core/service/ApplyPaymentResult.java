package com.stove.order.core.service;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.*;
import com.stove.order.core.domain.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ApplyPaymentResult {
    public static final String CONSUMER_GROUP = "order-payment-result";
    private final OrderRepository orders;
    private final ProcessedEventGuard guard;
    private final EntityManager entityManager;

    public void execute(String eventType, PaymentResult result) {
        if (eventType == null || eventType.isBlank() || eventType.length() > 60 || result == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 결과 입력이 올바르지 않습니다.");
        var order = orders.lockByOrderNo(result.orderNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제 결과의 주문이 없습니다."));
        orders.findByPaymentResultEventId(result.eventId()).ifPresent(previous -> {
            if (!previous.getOrderNo().equals(order.getOrderNo()))
                throw new BusinessException(ErrorCode.CONFLICT, "결과 이벤트 ID가 다른 주문에 사용되었습니다.");
        });
        if (order.paymentResult() != null) { order.apply(result); return; }
        if (!guard.firstDelivery(result.eventId(), CONSUMER_GROUP, eventType))
            throw new BusinessException(ErrorCode.CONFLICT, "Inbox와 주문의 결제 결과가 일치하지 않습니다.");
        order.apply(result);
        entityManager.flush();
    }
}

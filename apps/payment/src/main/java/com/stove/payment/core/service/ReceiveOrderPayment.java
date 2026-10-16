package com.stove.payment.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.*;
import com.stove.payment.core.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReceiveOrderPayment {
    public static final String CONSUMER_GROUP = "payment-order-request";
    private final PaymentRepository payments;
    private final ProcessedEventGuard guard;

    public Payment execute(PaymentOrderRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);

        // Compare the complete input before treating an Inbox hit as a duplicate.
        Payment existing = payments.findByOrderNo(request.orderNo()).orElse(null);
        if (existing != null) existing.requireSameRequest(request);
        payments.findByRequestEventId(request.eventId()).ifPresent(previous -> {
            if (!previous.getOrderNo().equals(request.orderNo())) {
                throw new BusinessException(ErrorCode.CONFLICT, "요청 이벤트 ID가 다른 주문에 사용되었습니다.");
            }
        });

        boolean firstDelivery = guard.firstDelivery(request.eventId(), CONSUMER_GROUP, EventType.ORDER_PLACED);
        if ((firstDelivery && existing != null) || (!firstDelivery && existing == null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Inbox와 결제 요청이 일치하지 않습니다.");
        }
        return existing != null ? existing : payments.saveAndFlush(Payment.receive(request));
    }
}

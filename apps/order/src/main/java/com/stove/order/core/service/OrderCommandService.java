package com.stove.order.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.OrderCanceledEvent;
import com.stove.common.event.payload.OrderCreatedEvent;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderNoGenerator;
import com.stove.order.core.domain.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 쓰기 트랜잭션. <b>DB 변경과 이벤트 적재를 항상 같은 트랜잭션</b>에서 처리한다(Outbox).
 * 외부 HTTP 호출은 이 클래스에 들어오지 않는다 — 트랜잭션 유지 시간을 짧게 가져가기 위함.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OrderCommandService {

    private static final String AGGREGATE = "Order";

    /** Kafka 컨슈머 그룹이자 Inbox 멱등 키. 리스너도 이 상수를 참조한다 — {@code ConsumerGroupRules} 참고. */
    public static final String CONSUMER_GROUP = "order";

    private final OrderRepository orderRepository;
    private final OutboxRecorder outboxRecorder;
    private final ProcessedEventGuard processedEventGuard;
    private final OrderNoGenerator orderNoGenerator;

    public Order createOrder(Long memberId, String currency, List<OrderLine> lines) {
        Order order = Order.create(orderNoGenerator.generate(), memberId, currency, lines);
        orderRepository.save(order);

        outboxRecorder.record(AGGREGATE, order.getOrderNo(),
                OrderCreatedEvent.of(order.getOrderNo(), memberId, order.getTotalAmount(), lines));

        log.info("주문 생성 orderNo={} memberId={} amount={}", order.getOrderNo(), memberId, order.getTotalAmount());
        return order;
    }

    /** 사용자 취소(결제 전). 결제 완료 이후 취소는 payment 의 환불 → PaymentCancelled 경로를 탄다. */
    public void cancelOrder(String orderNo, Long memberId, String reason) {
        Order order = findOrder(orderNo);
        order.requireOwner(memberId);
        order.cancel(reason);

        outboxRecorder.record(AGGREGATE, orderNo, OrderCanceledEvent.of(orderNo, memberId, reason));
    }

    /** payment.PaymentCompleted 수신 처리 */
    public void confirmPaid(String eventId, String eventType, String orderNo) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        findOrder(orderNo).markPaid();
        log.info("주문 결제 확정 orderNo={}", orderNo);
    }

    /** payment.PaymentCancelled 수신 처리(환불/보상 트랜잭션 결과 반영) */
    public void confirmCanceled(String eventId, String eventType, String orderNo, String reason) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        findOrder(orderNo).cancel(reason);
        log.info("주문 취소 반영 orderNo={} reason={}", orderNo, reason);
    }

    private Order findOrder(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "orderNo=" + orderNo));
    }
}

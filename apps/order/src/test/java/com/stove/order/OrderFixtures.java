package com.stove.order;

import com.stove.common.kafka.*;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.*;
import com.stove.order.core.domain.*;
import java.util.UUID;

public final class OrderFixtures {
    private OrderFixtures() {}
    public static String id() { return UUID.randomUUID().toString(); }
    public static PublishedProduct product() { return new PublishedProduct(1L, "Game-1", "한글 게임", 3, 10000, "KRW"); }
    public static OrderRequest request() { return new OrderRequest(id(), "Game-1", 3, 10000); }
    public static Order order() { return Order.create("usr_buyer", request(), product(), id()); }
    public static OrderPaymentCompletedEvent completed(Order o) {
        return OrderPaymentCompletedEvent.of(o.getOrderNo(), o.getRequestEventId(), id(), o.getBuyerSubject(), o.getAmount(), o.getCurrency());
    }
    public static OrderPaymentFailedEvent failed(Order o) {
        return OrderPaymentFailedEvent.of(o.getOrderNo(), o.getRequestEventId(), id(), o.getBuyerSubject(), o.getAmount(), o.getCurrency(), "DECLINED", "승인이 거절되었습니다.");
    }
    public static EventEnvelope envelope(DomainEvent e) { return new EventEnvelope(e.eventId(), e.eventType(), e.partitionKey(), ""); }
    public static PaymentResult result(OrderPaymentCompletedEvent e) {
        return new PaymentResult(e.eventId(), e.occurredAt(), e.orderNo(), e.requestEventId(), e.paymentId(), e.buyerSubject(), e.amount(), e.currency(), OrderStatus.PAID, null, null);
    }
}

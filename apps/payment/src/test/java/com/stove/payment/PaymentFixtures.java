package com.stove.payment;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import com.stove.payment.core.domain.*;
import java.time.Instant;
import java.util.UUID;

public final class PaymentFixtures {
    private PaymentFixtures() {}
    public static String id() { return UUID.randomUUID().toString(); }
    public static PaymentOrderRequest request() {
        return new PaymentOrderRequest(id(),Payment.now(),id(),"usr_buyer",1L,"Game-1","한글 게임",3,10000,0,10000,"KRW");
    }
    public static OrderPlacedEvent event(PaymentOrderRequest r) {
        return new OrderPlacedEvent(r.eventId(),r.occurredAt(),r.orderNo(),r.buyerSubject(),r.productId(),r.productCode(),r.title(),
                r.contentVersion(),r.listAmount(),r.discountAmount(),r.amount(),r.currency());
    }
    public static EventEnvelope envelope(OrderPlacedEvent e) { return new EventEnvelope(e.eventId(),e.eventType(),e.orderNo(),""); }
    public static PgResult approved(Payment p) { return result(p,PgResult.Decision.APPROVED); }
    public static PgResult result(Payment p,PgResult.Decision decision) {
        return new PgResult("SIMULATED",p.getPaymentId(),p.getOrderNo(),p.getAmount(),p.getCurrency(),id(),
                Instant.parse("2026-10-16T00:00:00.123456Z"),decision,
                decision==PgResult.Decision.APPROVED ? null : "DECLINED",decision==PgResult.Decision.APPROVED ? null : "Final decline");
    }
}

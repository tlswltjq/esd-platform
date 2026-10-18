package com.stove.license;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.OrderPaidEvent;
import com.stove.license.core.domain.PaidOrder;

import java.time.Instant;
import java.util.UUID;

public final class LicenseFixtures {
    private LicenseFixtures() {
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public static PaidOrder paid() {
        return paid("usr_buyer", 1L, "Game-1");
    }

    public static PaidOrder paid(String buyer, Long productId, String code) {
        return new PaidOrder(id(), Instant.parse("2026-10-17T01:02:03.123456Z"), id(), id(), id(), id(),
                buyer, productId, code, "First title", 3, 10000, "KRW");
    }

    public static OrderPaidEvent event(PaidOrder p) {
        return new OrderPaidEvent(p.eventId(), p.occurredAt(), p.orderNo(), p.requestEventId(), p.paymentResultEventId(),
                p.paymentId(), p.buyerSubject(), p.productId(), p.productCode(), p.title(), p.contentVersion(), p.amount(), p.currency());
    }

    public static EventEnvelope envelope(OrderPaidEvent e) {
        return new EventEnvelope(e.eventId(), e.eventType(), e.orderNo(), "");
    }
}

package com.stove.order.core.service;

import com.stove.common.kafka.contract.payload.OrderExpiredEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderStatus;
import java.util.UUID;

/** Called only on the first transition, in the transaction that expires the locked order. */
public class RecordOrderExpiry {
    private final OutboxRecorder outbox;
    public RecordOrderExpiry(OutboxRecorder outbox) { this.outbox=outbox; }
    public void execute(Order order) {
        if(order.getStatus()!=OrderStatus.EXPIRED)throw new IllegalArgumentException("Confirmed expiry is required");
        outbox.record("Order",order.getOrderNo(),new OrderExpiredEvent(UUID.randomUUID().toString(),order.getUpdatedAt(),
                order.getOrderNo(),order.getRequestEventId(),order.getBuyerSubject(),order.getAmount(),order.getCurrency(),order.getExpiresAt()));
    }
}

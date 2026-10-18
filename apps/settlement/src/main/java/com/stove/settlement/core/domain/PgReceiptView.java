package com.stove.settlement.core.domain;

import java.time.Instant;
import java.util.List;

public record PgReceiptView(PgSettlement statement,String status,String reportedBy,Instant reportedAt,
        Instant lastCheckedAt,Instant simulatedReceivedAt,List<ItemCheck> checks) {
    public record ItemCheck(String paymentId,String code,String reason) { }
    public PgReceiptView { checks=List.copyOf(checks); }
}

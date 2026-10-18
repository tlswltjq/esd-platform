package com.stove.mockpg.core.domain;

import java.time.Instant;
import java.util.List;

public record SettlementReport(String provider, String providerSettlementId, String currency,
        Instant cutoffAt, Instant paidAt, String status, long paymentAmountTotal,
        long pgFeeAmountTotal, long payoutAmount, long recordCount, List<Charge> items) {
    public SettlementReport { items = List.copyOf(items); }
}

package com.stove.settlement.core.domain;

/** Selects a new posting month or preserves matching evidence already recorded by the service. */
public interface RefundSettlementMonthPolicy {
    RefundMonthSelection select(RefundMonthInput input);
}

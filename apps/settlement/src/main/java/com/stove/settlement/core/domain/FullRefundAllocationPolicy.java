package com.stove.settlement.core.domain;

/** Computes the amounts to reverse after a confirmed full refund. */
public interface FullRefundAllocationPolicy {
    RefundAllocation allocate(SettlementSale originalSale);
}

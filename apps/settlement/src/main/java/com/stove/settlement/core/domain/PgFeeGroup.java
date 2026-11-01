package com.stove.settlement.core.domain;

public record PgFeeGroup(String sellerSubject, String settlementMonth, long recordCount,
                         long confirmedCount, long confirmedFeeAmount) {
    public PgFeeTotals totals() { return new PgFeeTotals(recordCount, confirmedCount, confirmedFeeAmount); }
}

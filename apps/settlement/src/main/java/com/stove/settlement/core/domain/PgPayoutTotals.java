package com.stove.settlement.core.domain;

/** Provider-reported amounts; neither bank verification nor a complete monthly bank balance. */
public record PgPayoutTotals(long recordCount,long linkedCount,long amount,long fee,long vat) {
    public long netAmount() {return Math.addExact(amount,Math.addExact(fee,vat));}
}

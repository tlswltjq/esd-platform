package com.stove.settlement.core.domain;

/** Positive approval charges or positive refund returns, each with explicit coverage. */
public record PgCostTotals(long recordCount,long confirmedCount,long fee,long vat) {
    public PgCostTotals {
        SettlementValues.require(recordCount>=0 && confirmedCount>=0 && confirmedCount<=recordCount && fee>=0 && vat>=0
                && (confirmedCount>0 || fee==0 && vat==0),"PG 비용의 확인 건수와 금액이 올바르지 않습니다.");
        Math.addExact(fee,vat);
    }
    public long confirmedAmount() {return Math.addExact(fee,vat);}
    public long pendingCount() {return recordCount-confirmedCount;}
    public Long amount() {return pendingCount()==0?confirmedAmount():null;}
}

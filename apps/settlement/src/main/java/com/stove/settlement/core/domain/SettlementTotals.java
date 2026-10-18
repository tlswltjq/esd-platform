package com.stove.settlement.core.domain;

public record SettlementTotals(long paidAmount,long platformFeeAmount,long sellerPayoutAmount,
        long pgFeeAmount,long platformNetAmount,long recordCount) {
    public SettlementTotals {
        SettlementValues.require(paidAmount>=0 && platformFeeAmount>=0 && sellerPayoutAmount>=0 && pgFeeAmount>=0 && recordCount>=0,
                "정산 합계가 올바르지 않습니다.");
        SettlementValues.require(paidAmount==Math.addExact(platformFeeAmount,sellerPayoutAmount)
                && platformFeeAmount==Math.addExact(pgFeeAmount,platformNetAmount),"정산 금액 보존식이 다릅니다.");
    }
    public static SettlementTotals empty() { return new SettlementTotals(0,0,0,0,0,0); }
    public SettlementTotals plus(SettlementTotals other) {
        return new SettlementTotals(Math.addExact(paidAmount,other.paidAmount),Math.addExact(platformFeeAmount,other.platformFeeAmount),
                Math.addExact(sellerPayoutAmount,other.sellerPayoutAmount),Math.addExact(pgFeeAmount,other.pgFeeAmount),
                Math.addExact(platformNetAmount,other.platformNetAmount),Math.addExact(recordCount,other.recordCount));
    }
}

package com.stove.settlement.core.domain;

/** 판매자 배분의 합계. PG 비용과 플랫폼의 비용 차감 후 잔액은 포함하지 않는다. */
public record SettlementTotals(long paidAmount, long platformFeeAmount, long sellerPayoutAmount, long recordCount) {
    public SettlementTotals {
        SettlementValues.require(paidAmount >= 0 && platformFeeAmount >= 0 && sellerPayoutAmount >= 0 && recordCount >= 0,
                "판매자 정산 합계가 올바르지 않습니다.");
        SettlementValues.require(paidAmount == Math.addExact(platformFeeAmount, sellerPayoutAmount),
                "판매자 배분 금액 보존식이 다릅니다.");
    }
    public static SettlementTotals empty() { return new SettlementTotals(0, 0, 0, 0); }
    public SettlementTotals plus(SettlementTotals other) {
        return new SettlementTotals(Math.addExact(paidAmount, other.paidAmount),
                Math.addExact(platformFeeAmount, other.platformFeeAmount),
                Math.addExact(sellerPayoutAmount, other.sellerPayoutAmount), Math.addExact(recordCount, other.recordCount));
    }
}

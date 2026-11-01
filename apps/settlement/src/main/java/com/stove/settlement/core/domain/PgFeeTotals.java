package com.stove.settlement.core.domain;

/** 확인된 비용의 부분합과 미확인 건수를 구분한다. */
public record PgFeeTotals(long recordCount, long confirmedCount, long confirmedFeeAmount) {
    public PgFeeTotals {
        SettlementValues.require(recordCount >= 0 && confirmedCount >= 0 && confirmedCount <= recordCount
                && confirmedFeeAmount >= 0 && (confirmedCount > 0 || confirmedFeeAmount == 0),
                "PG 비용 대조 합계가 올바르지 않습니다.");
    }
    public Long feeAmount() { return confirmedCount == recordCount ? confirmedFeeAmount : null; }
    public long pendingCount() { return recordCount - confirmedCount; }
    public String status() {
        return confirmedCount == recordCount ? "CONFIRMED" : confirmedCount == 0 ? "UNCONFIRMED" : "PARTIAL";
    }
}

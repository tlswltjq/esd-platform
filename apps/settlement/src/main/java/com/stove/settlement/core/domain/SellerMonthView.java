package com.stove.settlement.core.domain;

/** 판매자 마감 값과 독립된 현재 PG 비용 확인 현황이다. */
public record SellerMonthView(SellerMonthlySettlement month, PgFeeTotals fees) {
    public SellerMonthView {
        SettlementValues.require(month != null && fees != null, "판매자 월과 PG 비용 조회 근거가 필요합니다.");
        SettlementValues.consistent(month.getRecordCount() == fees.recordCount(), "판매자 월과 매출 건수가 다릅니다.");
    }
    public Long pgFeeAmount() { return fees.feeAmount(); }
    public Long platformNetAmount() {
        return pgFeeAmount() == null ? null : Math.subtractExact(month.getPlatformFeeAmount(), pgFeeAmount());
    }
}

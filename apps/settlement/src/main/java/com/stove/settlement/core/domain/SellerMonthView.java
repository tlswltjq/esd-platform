package com.stove.settlement.core.domain;

/** Seller closing facts combined with independently received PG charges and returns. */
public record SellerMonthView(SellerMonthlySettlement month,PgCostTotals saleCosts,PgCostTotals refundReturns) {
    public SellerMonthView {
        SettlementValues.require(month!=null && saleCosts!=null && refundReturns!=null,"판매자 월과 PG 비용 조회 근거가 필요합니다.");
        SettlementValues.consistent(month.getRecordCount()==saleCosts.recordCount() && month.getRefundCount()==refundReturns.recordCount(),
                "판매자 월과 매출·환불 건수가 다릅니다.");
    }
    public SellerMonthView(SellerMonthlySettlement month,PgFeeTotals fees) {
        this(month,new PgCostTotals(fees.recordCount(),fees.confirmedCount(),fees.confirmedFeeAmount(),0),
                new PgCostTotals(month.getRefundCount(),0,0,0));
    }
    public PgFeeTotals fees() {return new PgFeeTotals(saleCosts.recordCount(),saleCosts.confirmedCount(),saleCosts.confirmedAmount());}
    public long confirmedNetCost() {return Math.subtractExact(saleCosts.confirmedAmount(),refundReturns.confirmedAmount());}
    public Long pgFeeAmount() {return pendingPgCostCount()==0?confirmedNetCost():null;}
    public String pgCostStatus() {
        return pendingPgCostCount()==0?"CONFIRMED":saleCosts.confirmedCount()+refundReturns.confirmedCount()==0?"UNCONFIRMED":"PARTIAL";
    }
    public long pendingPgCostCount() {return Math.addExact(saleCosts.pendingCount(),refundReturns.pendingCount());}
    public Long platformNetAmount() {return pgFeeAmount()==null?null:Math.subtractExact(month.getNetPlatformFeeAmount(),pgFeeAmount());}
}

package com.stove.settlement.core.domain;

import java.util.List;

public record PgStatementTotals(long amount,long fee,long vat,long feeVatSum) {
    public PgStatementTotals {
        SettlementValues.require(Math.addExact(fee,vat)==feeVatSum,"PG 명세 수수료/VAT 전체 합계가 다릅니다.");
        Math.addExact(amount,feeVatSum);
    }
    public static PgStatementTotals sum(List<PgStatementRow> rows) {
        long amount=0;long fee=0;long vat=0;
        for(PgStatementRow row:rows) { amount=Math.addExact(amount,row.amount());fee=Math.addExact(fee,row.fee());vat=Math.addExact(vat,row.vat()); }
        return new PgStatementTotals(amount,fee,vat,Math.addExact(fee,vat));
    }
    public long netAmount() { return Math.addExact(amount,feeVatSum); }
}

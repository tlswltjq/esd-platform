package com.stove.settlement.core.domain;

/** 독립된 매출·PG 거래·비용을 조합한 조회 결과다. */
public record SettlementSaleView(SettlementSale sale, PgEvidence evidence, PgCost pgCost) {
    public SettlementSaleView {
        SettlementValues.require(sale != null && evidence != null, "매출과 PG 거래 조회 근거가 필요합니다.");
        SettlementValues.consistent(sale.getProvider().equals(evidence.provider())
                && sale.getProviderTransactionId().equals(evidence.transactionId()), "매출과 PG 거래가 다릅니다.");
        if (pgCost != null) pgCost.requireEvidence(evidence);
    }
    public Long platformNetAmount() {
        return pgCost == null ? null : Math.subtractExact(sale.getPlatformFeeAmount(), pgCost.getPgFeeAmount());
    }
    public String pgCostStatus() { return pgCost == null ? "UNCONFIRMED" : "CONFIRMED"; }
}

package com.stove.settlement.core.domain;

/** 독립된 매출·PG 거래·비용과 원매출의 환불 원장(없으면 null)을 조합한 조회 결과다. */
public record SettlementSaleView(SettlementSale sale, PgEvidence evidence, PgCost pgCost, PgFunding funding, SettlementRefund refund) {
    public SettlementSaleView {
        SettlementValues.require(sale != null && evidence != null, "매출과 PG 거래 조회 근거가 필요합니다.");
        SettlementValues.consistent(sale.getProvider().equals(evidence.provider())
                && sale.getProviderTransactionId().equals(evidence.transactionId()), "매출과 PG 거래가 다릅니다.");
        if (pgCost != null) pgCost.requireEvidence(evidence);
        if (funding != null) funding.requirePayment(evidence, sale.getAmount());
        SaleWithRefund.requireSameOrder(sale, refund);
    }
    public SettlementSaleView(SettlementSale sale, PgEvidence evidence, PgCost pgCost, PgFunding funding) { this(sale, evidence, pgCost, funding, null); }
    public SettlementSaleView(SettlementSale sale, PgEvidence evidence, PgCost pgCost) { this(sale, evidence, pgCost, null, null); }
    public SettlementSaleView withRefund(SettlementRefund linked) { return new SettlementSaleView(sale, evidence, pgCost, funding, linked); }
    public Long platformNetAmount() {
        return pgCost == null ? null : Math.subtractExact(sale.getPlatformFeeAmount(), pgCost.getPgFeeAmount());
    }
    public String pgCostStatus() { return pgCost == null ? "UNCONFIRMED" : "CONFIRMED"; }
}

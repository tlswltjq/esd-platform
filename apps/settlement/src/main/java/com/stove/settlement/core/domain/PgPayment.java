package com.stove.settlement.core.domain;

public record PgPayment(String paymentId, String orderNo, long amount, String currency,
                        PgEvidence evidence, PgCost cost, PgFunding funding) {
    public PgPayment {
        SettlementValues.require(SettlementValues.text(paymentId, 64) && SettlementValues.uuid(orderNo),
                "PG 결제 연결이 올바르지 않습니다.");
        SettlementValues.require(amount > 0 && "KRW".equals(currency) && evidence != null,
                "PG 결제액과 거래 근거가 올바르지 않습니다.");
        SettlementValues.require("PORTONE_TEST".equals(evidence.provider()) == (funding != null),
                "PortOne 원승인에는 검증된 지급 구성 근거가 필요합니다.");
        if (funding != null) funding.requirePayment(evidence, amount);
        if (cost != null) {
            cost.requireEvidence(evidence);
            SettlementValues.require(cost.getPgFeeAmount() <= amount, "PG 비용이 결제액을 초과합니다.");
        }
    }

    public PgPayment(String paymentId, String orderNo, long amount, String currency, PgEvidence evidence, PgCost cost) {
        this(paymentId, orderNo, amount, currency, evidence, cost, null);
    }

    public PgPayment(String paymentId, String orderNo, long amount, String currency, PgCost cost) {
        this(paymentId, orderNo, amount, currency, evidenceOf(cost), cost, null);
    }

    private static PgEvidence evidenceOf(PgCost cost) {
        SettlementValues.require(cost != null, "비용이 없으면 승인 거래 근거를 별도로 제공해야 합니다.");
        return new PgEvidence(cost.getProvider(), cost.getProviderTransactionId(), cost.getProviderOccurredAt());
    }

    public void requireOrder(PaidOrder order) {
        SettlementValues.consistent(paymentId.equals(order.paymentId()) && orderNo.equals(order.orderNo())
                && amount == order.amount() && currency.equals(order.currency()), "주문과 PG 결제 결과가 다릅니다.");
    }
}

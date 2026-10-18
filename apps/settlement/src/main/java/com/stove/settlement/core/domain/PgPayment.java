package com.stove.settlement.core.domain;

public record PgPayment(String paymentId,String orderNo,long amount,String currency,PgCost cost) {
    public PgPayment {
        SettlementValues.require(SettlementValues.text(paymentId,64) && SettlementValues.uuid(orderNo),"PG 결제 연결이 올바르지 않습니다.");
        SettlementValues.require(amount>0 && "KRW".equals(currency) && cost!=null && cost.getPgFeeAmount()<=amount,
                "PG 결제액과 비용이 올바르지 않습니다.");
    }
    public void requireOrder(PaidOrder order) {
        SettlementValues.consistent(paymentId.equals(order.paymentId()) && orderNo.equals(order.orderNo())
                && amount==order.amount() && currency.equals(order.currency()),"주문과 PG 결제 결과가 다릅니다.");
    }
}

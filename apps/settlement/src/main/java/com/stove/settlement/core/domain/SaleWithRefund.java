package com.stove.settlement.core.domain;

/** 매출과 그 원매출을 상계한 환불 원장(전액 환불이라 주문당 최대 1건, 없으면 null)을 함께 조회한 결과다. */
public record SaleWithRefund(SettlementSale sale, SettlementRefund refund) {
    public SaleWithRefund {
        SettlementValues.require(sale != null, "조회한 매출이 필요합니다.");
        requireSameOrder(sale, refund);
    }

    static void requireSameOrder(SettlementSale sale, SettlementRefund refund) {
        if (refund != null) SettlementValues.consistent(sale.getOrderNo().equals(refund.getOrderNo()), "매출과 환불의 원주문이 다릅니다.");
    }
}

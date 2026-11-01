package com.stove.settlement.core.domain;

public record SellerMonthKey(String seller, String month) {
    public SellerMonthKey {
        SettlementValues.require(SettlementValues.text(seller,100), "판매자 식별자가 필요합니다.");
        new SettlementMonth(month);
    }
}

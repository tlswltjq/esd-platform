package com.stove.settlement.core.domain;

public record SettlementQuery(String sellerSubject,String month,int page,int size) {
    public SettlementQuery {
        SettlementValues.require(sellerSubject==null || SettlementValues.text(sellerSubject,100),"판매자가 올바르지 않습니다.");
        if(month!=null) new SettlementMonth(month);
        SettlementValues.require(page>=0 && size>=1 && size<=100,"페이지가 올바르지 않습니다.");
    }
}

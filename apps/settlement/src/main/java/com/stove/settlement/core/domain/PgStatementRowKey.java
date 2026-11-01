package com.stove.settlement.core.domain;

public record PgStatementRowKey(String token,String orderNo) {
    public PgStatementRowKey {
        SettlementValues.require(SettlementValues.text(token,100) && SettlementValues.text(orderNo,100),"PG 명세의 원거래 연결 키가 필요합니다.");
    }
}

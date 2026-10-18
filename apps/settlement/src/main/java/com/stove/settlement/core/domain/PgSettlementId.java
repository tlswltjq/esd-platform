package com.stove.settlement.core.domain;

public record PgSettlementId(String value) {
    public PgSettlementId {
        SettlementValues.require(SettlementValues.text(value,64),"PG 정산번호가 올바르지 않습니다.");
    }
}

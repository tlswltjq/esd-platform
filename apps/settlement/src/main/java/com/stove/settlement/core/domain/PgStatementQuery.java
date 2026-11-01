package com.stove.settlement.core.domain;

import java.time.LocalDate;

public record PgStatementQuery(String dateType, LocalDate baseDate) {
    public PgStatementQuery {
        SettlementValues.require(("DUE".equals(dateType) || "SETTLE".equals(dateType)) && baseDate != null,
                "PG 명세의 조회 기준과 날짜가 필요합니다.");
    }
}

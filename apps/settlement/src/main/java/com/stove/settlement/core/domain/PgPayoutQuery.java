package com.stove.settlement.core.domain;

import java.time.LocalDate;
import java.time.YearMonth;

public record PgPayoutQuery(String merchantId,String environment,String month) {
    public PgPayoutQuery {
        SettlementValues.require(SettlementValues.text(merchantId,100) && ("LIVE".equals(environment) || "TEST".equals(environment)),
                "PG 지급 조회의 가맹점과 환경이 필요합니다.");
        new SettlementMonth(month);
    }
    public LocalDate start() {return YearMonth.parse(month).atDay(1);}
    public LocalDate end() {return start().plusMonths(1);}
}

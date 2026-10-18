package com.stove.settlement.core.domain;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

public record SettlementMonth(String value) {
    public SettlementMonth {
        SettlementValues.require(value!=null && value.matches("[0-9]{4}-(0[1-9]|1[0-2])") && !value.startsWith("0000"),
                "월은 yyyy-MM 형식이어야 합니다.");
    }
    public static SettlementMonth at(Instant instant) {
        return new SettlementMonth(YearMonth.from(instant.atZone(ZoneId.of("Asia/Seoul"))).toString());
    }
}

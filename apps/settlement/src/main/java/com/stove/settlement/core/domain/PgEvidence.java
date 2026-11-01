package com.stove.settlement.core.domain;

import java.time.Instant;

public record PgEvidence(String provider, String transactionId, Instant occurredAt) {
    public PgEvidence {
        SettlementValues.require(("SIMULATED".equals(provider) || "PORTONE_TEST".equals(provider))
                && SettlementValues.text(transactionId, 100) && SettlementValues.microsecond(occurredAt),
                "PG 승인 거래 근거가 올바르지 않습니다.");
    }
}

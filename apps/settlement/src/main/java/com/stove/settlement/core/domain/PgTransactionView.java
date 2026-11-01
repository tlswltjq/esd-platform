package com.stove.settlement.core.domain;

import java.time.Instant;

public record PgTransactionView(PgPayment payment, Instant receivedAt, Instant costReceivedAt) {
    public PgTransactionView {
        SettlementValues.require(payment != null && SettlementValues.microsecond(receivedAt), "PG 거래 조회 근거가 필요합니다.");
        SettlementValues.require(payment.cost() == null ? costReceivedAt == null : SettlementValues.microsecond(costReceivedAt),
                "PG 비용과 접수 시각이 일치해야 합니다.");
    }
}

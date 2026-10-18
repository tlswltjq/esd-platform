package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.PgReceiptView;
import com.stove.settlement.core.domain.PgSettlement;
import java.time.Instant;
import java.util.List;

public record PgReceiptResponse(PgSettlement statement,String status,String reportedBy,Instant reportedAt,
        Instant lastCheckedAt,Instant simulatedReceivedAt,List<PgReceiptView.ItemCheck> checks) {
    public static PgReceiptResponse from(PgReceiptView view) {
        return new PgReceiptResponse(view.statement(),view.status(),view.reportedBy(),view.reportedAt(),view.lastCheckedAt(),view.simulatedReceivedAt(),view.checks());
    }
}

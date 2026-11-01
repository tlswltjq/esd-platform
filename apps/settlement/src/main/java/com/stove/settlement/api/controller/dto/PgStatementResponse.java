package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.PgStatement;
import com.stove.settlement.core.domain.PgStatementScope;
import com.stove.settlement.core.domain.PgStatementTotals;
import java.time.Instant;
import java.time.LocalDate;

public record PgStatementResponse(Long id,PgStatementScope scope,String dateType,LocalDate baseDate,String fingerprint,
        PgStatementTotals totals,long reportedNetAmount,long recordCount,int pageCount,String status,Instant receivedAt,String collectedBy,Instant checkedAt) {
    public static PgStatementResponse from(PgStatement statement) {
        return new PgStatementResponse(statement.getId(),statement.scope(),statement.getDateType(),statement.getBaseDate(),statement.getFingerprint(),
                statement.totals(),statement.totals().netAmount(),statement.getRecordCount(),statement.getPageCount(),statement.getStatus(),
                statement.getReceivedAt(),statement.getCollectedBy(),statement.getCheckedAt());
    }
}

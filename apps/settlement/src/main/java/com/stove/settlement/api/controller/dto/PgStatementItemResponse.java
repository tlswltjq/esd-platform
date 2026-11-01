package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.PgStatementItem;
import com.stove.settlement.core.domain.PgStatementRow;
import java.time.Instant;

public record PgStatementItemResponse(int position,PgStatementRow reported,long reportedNetAmount,String checkCode,String paymentId,Instant checkedAt) {
    public static PgStatementItemResponse from(PgStatementItem item) {
        return new PgStatementItemResponse(item.getItemPosition(),item.getEntry().value(),item.getEntry().value().netAmount(),item.getCheckCode(),item.getPaymentId(),item.getCheckedAt());
    }
}

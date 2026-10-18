package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SellerMonthlySettlement;
import java.time.Instant;

public record MonthResponse(String settlementMonth,String status,long paidAmount,long platformFeeAmount,
        long sellerPayoutAmount,long recordCount,Instant closedAt) {
    public static MonthResponse from(SellerMonthlySettlement month) {
        return new MonthResponse(month.getSettlementMonth(),month.getStatus(),month.getPaidAmount(),month.getPlatformFeeAmount(),
                month.getSellerPayoutAmount(),month.getRecordCount(),month.getClosedAt());
    }
}

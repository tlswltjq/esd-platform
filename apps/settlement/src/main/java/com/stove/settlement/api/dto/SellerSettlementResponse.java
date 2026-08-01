package com.stove.settlement.api.dto;

import com.stove.settlement.domain.SellerSettlement;
import java.time.Instant;

public record SellerSettlementResponse(
        Long sellerId,
        String settlementMonth,
        long grossAmount,
        long feeAmount,
        long netAmount,
        int recordCount,
        String taxInvoiceNo,
        Instant closedAt
) {
    public static SellerSettlementResponse from(SellerSettlement settlement) {
        return new SellerSettlementResponse(
                settlement.getSellerId(), settlement.getSettlementMonth(), settlement.getGrossAmount(),
                settlement.getFeeAmount(), settlement.getNetAmount(), settlement.getRecordCount(),
                settlement.getTaxInvoiceNo(), settlement.getClosedAt());
    }
}

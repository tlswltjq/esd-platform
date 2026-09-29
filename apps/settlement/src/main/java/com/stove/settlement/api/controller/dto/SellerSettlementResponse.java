package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SellerSettlement;
import java.time.Instant;

public record SellerSettlementResponse(
        Long sellerId,
        String settlementMonth,
        long grossAmount,
        long feeAmount,
        long netAmount,
        long baseGrossAmount,
        long baseFeeAmount,
        long baseNetAmount,
        long adjustmentGrossAmount,
        long adjustmentFeeAmount,
        long adjustmentNetAmount,
        int recordCount,
        String taxInvoiceNo,
        String taxInvoiceStatus,
        Instant closedAt
) {
    public static SellerSettlementResponse from(SellerSettlement settlement) {
        return new SellerSettlementResponse(
                settlement.getSellerId(), settlement.getSettlementMonth(), settlement.getGrossAmount(),
                settlement.getFeeAmount(), settlement.getNetAmount(), settlement.getBaseGrossAmount(),
                settlement.getBaseFeeAmount(), settlement.getBaseNetAmount(),
                settlement.getAdjustmentGrossAmount(), settlement.getAdjustmentFeeAmount(),
                settlement.getAdjustmentNetAmount(), settlement.getRecordCount(),
                settlement.getTaxInvoiceNo(), settlement.getTaxInvoiceNo() == null
                        ? "NOT_ISSUED" : "SIMULATED", settlement.getClosedAt());
    }
}

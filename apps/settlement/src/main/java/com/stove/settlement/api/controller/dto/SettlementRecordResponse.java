package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.RecordType;
import com.stove.settlement.core.domain.SaleType;
import com.stove.settlement.core.domain.SettlementRecord;
import java.math.BigDecimal;

public record SettlementRecordResponse(
        String orderNo,
        Long productId,
        Long sellerId,
        SaleType saleType,
        RecordType recordType,
        long grossAmount,
        BigDecimal feeRate,
        long feeAmount,
        long netAmount,
        String settlementMonth,
        boolean closed
) {
    public static SettlementRecordResponse from(SettlementRecord record) {
        return new SettlementRecordResponse(
                record.getOrderNo(), record.getProductId(), record.getSellerId(),
                record.getSaleType(), record.getRecordType(), record.getGrossAmount(),
                record.getFeeRate(), record.getFeeAmount(), record.getNetAmount(),
                record.getSettlementMonth(), record.isClosed());
    }
}

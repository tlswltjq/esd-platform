package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SellerMonthBalance;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import java.time.Instant;

/** {@code carriedIn}·{@code payable}·{@code carriedOut} are fixed only for CLOSED months; OPEN months return null (decisions 121·130). */
public record MonthResponse(String settlementMonth,String status,long paidAmount,long platformFeeAmount,
        long sellerPayoutAmount,long recordCount,Instant closedAt,
        long refundAmount,long refundedPlatformFeeAmount,long refundedSellerPayoutAmount,long refundCount,
        long netPaidAmount,long netPlatformFeeAmount,long netSellerPayoutAmount,
        Long carriedIn,Long payable,Long carriedOut) {
    public static MonthResponse from(SellerMonthlySettlement month,SellerMonthBalance balance) {
        return new MonthResponse(month.getSettlementMonth(),month.getStatus(),month.getPaidAmount(),month.getPlatformFeeAmount(),
                month.getSellerPayoutAmount(),month.getRecordCount(),month.getClosedAt(),month.getRefundAmount(),
                month.getRefundedPlatformFeeAmount(),month.getRefundedSellerPayoutAmount(),month.getRefundCount(),
                month.getNetPaidAmount(),month.getNetPlatformFeeAmount(),month.getNetSellerPayoutAmount(),
                balance.carriedIn(),balance.payable(),balance.carriedOut());
    }
}

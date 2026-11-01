package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SettlementRefund;
import java.time.Instant;

public record RefundResponse(String refundId,String orderNo,Long productId,String productCode,String title,long contentVersion,
        long amountDelta,long platformFeeAmountDelta,long sellerPayoutAmountDelta,String currency,
        Instant confirmedAt,Instant receivedAt,String refundMonth,String settlementMonth,String monthReason) {
    public static RefundResponse from(SettlementRefund refund) {
        return new RefundResponse(refund.getRefundId(),refund.getOrderNo(),refund.getProductId(),refund.getProductCode(),refund.getTitle(),
                refund.getContentVersion(),Math.negateExact(refund.getAmount()),Math.negateExact(refund.getPlatformFeeAmount()),
                Math.negateExact(refund.getSellerPayoutAmount()),refund.getCurrency(),refund.getConfirmedAt(),refund.getReceivedAt(),
                refund.getRefundMonth(),refund.getSettlementMonth(),refund.getMonthReason());
    }
}

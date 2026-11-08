package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SaleWithRefund;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.domain.SettlementSale;
import java.time.Instant;

public record SaleResponse(String orderNo,Long productId,String productCode,String title,long contentVersion,
        long amount,long platformFeeAmount,long sellerPayoutAmount,String currency,String saleMonth,String settlementMonth,
        Instant occurredAt,Instant receivedAt,SaleRefundResponse refund) {
    public static SaleResponse from(SaleWithRefund row) { return from(row.sale(),row.refund()); }
    public static SaleResponse from(SettlementSale sale,SettlementRefund refund) {
        return new SaleResponse(sale.getOrderNo(),sale.getProductId(),sale.getProductCode(),sale.getTitle(),sale.getContentVersion(),
                sale.getAmount(),sale.getPlatformFeeAmount(),sale.getSellerPayoutAmount(),sale.getCurrency(),sale.getSaleMonth(),
                sale.getSettlementMonth(),sale.getOccurredAt(),sale.getReceivedAt(),SaleRefundResponse.from(refund));
    }
}

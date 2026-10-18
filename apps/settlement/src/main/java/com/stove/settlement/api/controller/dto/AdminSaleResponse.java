package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgCost;
import com.stove.settlement.core.domain.SettlementSale;

public record AdminSaleResponse(SaleResponse sale,String sellerSubject,String platformPolicyId,int platformFeeRateBps,
        PgCost pgCost,long platformNetAmount,PaidOrder source) {
    public static AdminSaleResponse from(SettlementSale sale) {
        return new AdminSaleResponse(SaleResponse.from(sale),sale.getSellerSubject(),sale.getPlatformPolicyId(),
                sale.getPlatformFeeRateBps(),sale.getPgCost(),sale.getPlatformNetAmount(),sale.source());
    }
}

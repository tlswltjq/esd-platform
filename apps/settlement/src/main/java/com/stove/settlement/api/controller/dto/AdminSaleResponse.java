package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgCost;
import com.stove.settlement.core.domain.PgEvidence;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleView;

public record AdminSaleResponse(SaleResponse sale, String sellerSubject, String platformPolicyId, int platformFeeRateBps,
        PgCost pgCost, Long platformNetAmount, PaidOrder source, String pgCostStatus, PgEvidence pgTransaction) {
    public static AdminSaleResponse from(SettlementSaleView view) {
        SettlementSale sale = view.sale();
        return new AdminSaleResponse(SaleResponse.from(sale), sale.getSellerSubject(), sale.getPlatformPolicyId(),
                sale.getPlatformFeeRateBps(), view.pgCost(), view.platformNetAmount(), sale.source(), view.pgCostStatus(), view.evidence());
    }
}

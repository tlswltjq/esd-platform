package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.PgRefundCancellation;
import com.stove.settlement.core.domain.SettlementRefund;
import java.time.Instant;
import java.util.List;

public record AdminRefundResponse(AdminRefundSummaryResponse summary,ConfirmedRefund source,String provider,
        Instant supplierCancelledAt,List<PgRefundCancellation> cancellations) {
    public static AdminRefundResponse from(SettlementRefund refund) {
        return new AdminRefundResponse(AdminRefundSummaryResponse.from(refund),refund.confirmation(),refund.getProvider(),
                refund.getSupplierCancelledAt(),List.copyOf(refund.getCancellations()));
    }
}

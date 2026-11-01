package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SettlementRefund;

public record AdminRefundSummaryResponse(RefundResponse refund,String sellerSubject,String eventId,String paymentId,String source,
        String allocationPolicyId,String originalPlatformPolicyId,String monthPolicyId) {
    public static AdminRefundSummaryResponse from(SettlementRefund refund) {
        return new AdminRefundSummaryResponse(RefundResponse.from(refund),refund.getSellerSubject(),refund.getEventId(),refund.getPaymentId(),
                refund.getSource(),refund.getAllocationPolicyId(),refund.getOriginalPlatformPolicyId(),refund.getMonthPolicyId());
    }
}

package com.stove.settlement.core.domain;

/** Reverses the original allocation without consulting today's fee rate or provider costs. */
public final class ReverseOriginalFullRefundPolicy implements FullRefundAllocationPolicy {
    @Override
    public RefundAllocation allocate(SettlementSale originalSale) {
        SettlementValues.require(originalSale != null, "원매출이 필요합니다.");
        return new RefundAllocation("FULL_REFUND_REVERSE_ORIGINAL_V1", originalSale.getOrderNo(),
                originalSale.getPlatformPolicyId(), originalSale.getAmount(), originalSale.getPlatformFeeAmount(),
                originalSale.getSellerPayoutAmount());
    }
}

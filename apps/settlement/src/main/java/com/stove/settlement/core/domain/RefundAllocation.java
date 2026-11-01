package com.stove.settlement.core.domain;

/** Positive reversal amounts; posting deltas are negative. Original sales remain immutable. */
public record RefundAllocation(String policyId, String orderNo, String originalPlatformPolicyId,
        long amount, long platformFeeAmount, long sellerPayoutAmount) {
    public RefundAllocation {
        SettlementValues.require(SettlementValues.text(policyId, 100) && SettlementValues.uuid(orderNo)
                && SettlementValues.text(originalPlatformPolicyId, 100), "환불 배분의 정책·원매출 근거가 필요합니다.");
        SettlementValues.require(amount > 0 && platformFeeAmount >= 0 && platformFeeAmount <= amount
                && sellerPayoutAmount == amount - platformFeeAmount, "환불 배분의 금액 보존식이 다릅니다.");
    }

    public long paidAmountDelta() { return Math.negateExact(amount); }
    public long platformFeeAmountDelta() { return Math.negateExact(platformFeeAmount); }
    public long sellerPayoutAmountDelta() { return Math.negateExact(sellerPayoutAmount); }
}

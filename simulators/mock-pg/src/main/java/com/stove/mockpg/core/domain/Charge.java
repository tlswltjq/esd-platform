package com.stove.mockpg.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public record Charge(String provider, String paymentId, String orderNo, long amount, String currency,
        String providerTransactionId, Instant providerOccurredAt, Decision decision, String reasonCode,
        String reason, String pgFeePolicyId, Integer pgFeeRateBps, Long pgFeeAmount) {
    public enum Decision { APPROVED, DECLINED }

    public void requireRequest(ChargeRequest request) {
        if (!paymentId.equals(request.paymentId()) || !orderNo.equals(request.orderNo())
                || amount != request.amount() || !currency.equals(request.currency())) {
            throw new ProviderException(409, "CHARGE_CONFLICT");
        }
    }

    public static long fee(long amount, int bps) {
        if (amount <= 0 || bps < 0 || bps > 10000) throw new IllegalArgumentException("Invalid fee basis");
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).longValueExact();
    }
}

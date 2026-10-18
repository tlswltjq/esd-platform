package com.stove.settlement.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record PlatformFeePolicy(String policyId,int rateBps) {
    public PlatformFeePolicy {
        SettlementValues.require(SettlementValues.text(policyId,100) && rateBps>=0 && rateBps<=10000,"플랫폼 정책이 올바르지 않습니다.");
    }
    public long fee(long amount) {
        SettlementValues.require(amount>0,"양수 결제액이 필요합니다.");
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rateBps))
                .divide(BigDecimal.valueOf(10000),0,RoundingMode.HALF_UP).longValueExact();
    }
}

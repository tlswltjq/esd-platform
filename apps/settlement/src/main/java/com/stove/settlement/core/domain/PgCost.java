package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable @Getter @EqualsAndHashCode
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PgCost {
    @Column(nullable=false,length=30,updatable=false) private String provider;
    @Column(nullable=false,length=100,updatable=false) private String providerTransactionId;
    @Column(nullable=false,updatable=false) private Instant providerOccurredAt;
    @Column(nullable=false,length=100,updatable=false) private String pgFeePolicyId;
    @Column(nullable=false,updatable=false) private int pgFeeRateBps;
    @Column(nullable=false,updatable=false) private long pgFeeAmount;

    public PgCost(String provider,String transactionId,Instant occurredAt,String policyId,int rateBps,long fee) {
        SettlementValues.require("SIMULATED".equals(provider) && SettlementValues.text(transactionId,100)
                && SettlementValues.microsecond(occurredAt),"PG 거래 근거가 올바르지 않습니다.");
        SettlementValues.require(SettlementValues.text(policyId,100) && rateBps>=0 && rateBps<=10000 && fee>=0,
                "확정 PG 비용이 필요합니다.");
        this.provider=provider; this.providerTransactionId=transactionId; this.providerOccurredAt=occurredAt;
        this.pgFeePolicyId=policyId; this.pgFeeRateBps=rateBps; this.pgFeeAmount=fee;
    }
}

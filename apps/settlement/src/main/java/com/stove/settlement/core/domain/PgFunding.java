package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 현재 토스페이 TEST 계약에서 확인한 원승인 지급 구성과 외부 부담 정책이다. */
@Embeddable @Getter @EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgFunding {
    @Column(updatable = false) private long fundingTotalAmount;
    @Column(updatable = false) private long pgPaidAmount;
    @Column(updatable = false) private long pgDiscountAmount;
    @Column(updatable = false, length = 100) private String providerStoreId;
    @Column(updatable = false, length = 100) private String providerChannelKey;
    @Column(updatable = false, length = 30) private String providerChannelType;
    @Column(updatable = false, length = 30) private String providerPgProvider;
    @Column(updatable = false, length = 100) private String benefitPolicyId;

    public PgFunding(long total, long paid, long discount, String storeId, String channelKey) {
        SettlementValues.require(total > 0 && paid >= 0 && paid <= total && discount == total - paid,
                "PG 원승인 금액 구성이 올바르지 않습니다.");
        SettlementValues.require(SettlementValues.text(storeId, 100) && SettlementValues.text(channelKey, 100),
                "외부 부담 혜택의 상점·채널 근거가 필요합니다.");
        fundingTotalAmount = total; pgPaidAmount = paid; pgDiscountAmount = discount;
        providerStoreId = storeId; providerChannelKey = channelKey;
        providerChannelType = "TEST"; providerPgProvider = "TOSSPAY_V2";
        benefitPolicyId = "TOSSPAY_EXTERNAL_BENEFIT_V1";
    }

    public void requirePayment(PgEvidence evidence, long amount) {
        SettlementValues.consistent(evidence != null && "PORTONE_TEST".equals(evidence.provider()) && fundingTotalAmount == amount,
                "PG 승인 거래와 지급 구성 근거가 다릅니다.");
    }
}

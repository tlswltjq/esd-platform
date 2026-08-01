package com.stove.settlement.core.domain;

import com.stove.settlement.core.domain.SaleType;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수수료 정책. 자체 판매/입점 판매 구분이 정산 로직의 첫 분기다.
 * 정책이 늘어나면(프로모션 할인 수수료, 지역별 요율 등) 이 클래스만 확장한다.
 */
@Component
@RequiredArgsConstructor
public class FeePolicy {

    private final SettlementProperties properties;

    public SaleType saleTypeOf(Long sellerId) {
        return properties.selfSellerId().equals(sellerId) ? SaleType.SELF : SaleType.PARTNER;
    }

    public BigDecimal feeRateOf(SaleType saleType) {
        return saleType == SaleType.SELF ? BigDecimal.ZERO : properties.partnerFeeRate();
    }
}

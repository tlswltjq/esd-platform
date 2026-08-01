package com.stove.settlement.core.domain;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param selfSellerId    자체 판매(스마일게이트) 판매자 ID
 * @param partnerFeeRate  입점 판매 중개 수수료율
 */
@ConfigurationProperties(prefix = "stove.settlement")
public record SettlementProperties(Long selfSellerId, BigDecimal partnerFeeRate) {

    public SettlementProperties {
        selfSellerId = selfSellerId == null ? 1L : selfSellerId;
        partnerFeeRate = partnerFeeRate == null ? new BigDecimal("0.3000") : partnerFeeRate;
    }
}

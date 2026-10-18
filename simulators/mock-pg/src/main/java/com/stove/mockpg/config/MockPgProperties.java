package com.stove.mockpg.config;

import com.stove.mockpg.core.domain.Charge;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mock-pg")
public record MockPgProperties(String paymentKey, String settlementKey, String controlKey,
        Charge.Decision decision, String feePolicyId, int feeRateBps) {
    public MockPgProperties {
        if (paymentKey == null || settlementKey == null || controlKey == null
                || paymentKey.isBlank() || settlementKey.isBlank() || controlKey.isBlank()
                || new HashSet<String>(List.of(paymentKey, settlementKey, controlKey)).size() != 3) {
            throw new IllegalArgumentException("Three distinct PG client keys are required");
        }
        if (decision == null || feePolicyId == null || feePolicyId.isBlank() || feePolicyId.length() > 100
                || feeRateBps < 0 || feeRateBps > 10000) throw new IllegalArgumentException("Invalid PG policy");
    }
}

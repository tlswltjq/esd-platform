package com.stove.payment.config;

import com.stove.payment.core.domain.PgResult;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.payment.simulated")
public record SimulatedPgProperties(PgResult.Decision result) {
    public SimulatedPgProperties {
        if (result==null) throw new IllegalArgumentException("Simulated PG result is required");
    }
}

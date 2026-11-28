package com.stove.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.payment.pg")
public record PgProperties(String provider) {
    public PgProperties {
        if (!("SIMULATED".equals(provider) || "PORTONE_TEST".equals(provider)))
            throw new IllegalArgumentException("Unknown payment provider");
    }
}

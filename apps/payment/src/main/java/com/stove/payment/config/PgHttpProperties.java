package com.stove.payment.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.payment.pg")
public record PgHttpProperties(String provider, URI baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
    public PgHttpProperties {
        if (!("SIMULATED".equals(provider) || "PORTONE_TEST".equals(provider)))
            throw new IllegalArgumentException("Unknown payment provider");
        if ("SIMULATED".equals(provider) && (baseUrl==null || baseUrl.getHost()==null
                || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || apiKey==null || apiKey.isBlank() || connectTimeout==null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout==null || readTimeout.isNegative() || readTimeout.isZero()))
            throw new IllegalArgumentException("Explicit PG provider, HTTP endpoint, key and timeouts are required");
    }

    @Override public String toString() { return "PgHttpProperties[provider=" + provider + ", credentials=<redacted>]"; }
}

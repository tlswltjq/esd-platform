package com.stove.download.infrastructure.cdn;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stove.cdn")
public record CdnProperties(String baseUrl, String signingKey, Duration ttl) {

    public CdnProperties {
        baseUrl = baseUrl == null ? "https://cdn.stove.local" : baseUrl;
        signingKey = signingKey == null ? "local-dev-signing-key" : signingKey;
        ttl = ttl == null ? Duration.ofMinutes(10) : ttl;
    }
}

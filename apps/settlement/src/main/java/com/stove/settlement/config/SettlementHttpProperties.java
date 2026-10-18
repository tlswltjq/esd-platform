package com.stove.settlement.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.settlement")
public record SettlementHttpProperties(URI pgBaseUrl,String pgApiKey,URI catalogBaseUrl,String catalogKey,
        Duration connectTimeout,Duration readTimeout) {
    public SettlementHttpProperties {
        if(!http(pgBaseUrl) || !http(catalogBaseUrl) || pgApiKey==null || pgApiKey.isBlank() || catalogKey==null || catalogKey.isBlank()
                || connectTimeout==null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout==null || readTimeout.isNegative() || readTimeout.isZero())
            throw new IllegalArgumentException("PG/Catalog endpoints, keys and timeouts are required");
    }
    private static boolean http(URI uri) {
        return uri!=null && uri.getHost()!=null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
    }
}

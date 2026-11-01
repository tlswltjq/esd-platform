package com.stove.settlement.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.settlement.portone")
public record PortOneSettlementProperties(URI baseUrl, String apiSecret, String storeId, String channelKey,
        Duration connectTimeout, Duration readTimeout) {
    public void requireConfigured() {
        boolean loopback = baseUrl != null && ("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                && ("127.0.0.1".equals(baseUrl.getHost()) || "localhost".equals(baseUrl.getHost()));
        boolean provider = baseUrl != null && "https".equals(baseUrl.getScheme()) && "api.portone.io".equals(baseUrl.getHost());
        if ((!loopback && !provider) || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || (baseUrl.getPath() != null && !baseUrl.getPath().isEmpty() && !"/".equals(baseUrl.getPath()))
                || !present(apiSecret) || !present(storeId) || storeId.length() > 100 || !present(channelKey) || channelKey.length() > 100
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero())
            throw new IllegalArgumentException("Explicit PortOne TEST lookup endpoint, credentials, store and channel are required");
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    @Override public String toString() { return "PortOneSettlementProperties[credentials=REDACTED]"; }
}

package com.stove.payment.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.payment.portone")
public record PortOneProperties(URI baseUrl, String apiSecret, String storeId, String channelKey,
        String webhookSecret, Duration connectTimeout, Duration readTimeout, URI webhookUrl) {
    public void requireConfigured() {
        boolean loopback = baseUrl != null && ("127.0.0.1".equals(baseUrl.getHost()) || "localhost".equals(baseUrl.getHost()));
        boolean provider = baseUrl != null && "https".equals(baseUrl.getScheme()) && "api.portone.io".equals(baseUrl.getHost());
        if ((!loopback && !provider) || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || !present(apiSecret) || !present(storeId) || !present(channelKey) || !present(webhookSecret)
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.compareTo(Duration.ofSeconds(60)) < 0
                || webhookUrl == null || !"https".equals(webhookUrl.getScheme()) || webhookUrl.getHost() == null
                || webhookUrl.getUserInfo() != null || webhookUrl.getQuery() != null || webhookUrl.getFragment() != null) {
            throw new IllegalArgumentException("Explicit PortOne test configuration and webhook secret are required");
        }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    @Override public String toString() { return "PortOneProperties[credentials=<redacted>]"; }
}

package com.stove.order.infrastructure.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stove.catalog")
public record CatalogProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public CatalogProperties {
        baseUrl = baseUrl == null ? "http://localhost:8081" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(2) : readTimeout;
    }
}

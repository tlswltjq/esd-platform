package com.stove.download.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stove.download")
public record DownloadClientProperties(String licenseBaseUrl, String catalogBaseUrl,
        String catalogServiceKey, Duration connectTimeout, Duration readTimeout) {
    public DownloadClientProperties {
        requireBaseUrl(licenseBaseUrl);
        requireBaseUrl(catalogBaseUrl);
        if (catalogServiceKey == null || catalogServiceKey.isBlank())
            throw new IllegalArgumentException("Download requires a Catalog service key");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        if (connectTimeout.isNegative() || connectTimeout.isZero() || readTimeout.isNegative() || readTimeout.isZero())
            throw new IllegalArgumentException("HTTP timeouts must be positive");
    }

    private static void requireBaseUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Download requires service base URLs");
        URI uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())))
            throw new IllegalArgumentException("Invalid service base URL");
    }
}

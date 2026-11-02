package com.stove.catalog.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stove.catalog.download-storage")
public record DownloadStorageProperties(boolean enabled, String endpoint, String region, String bucket,
        String accessKey, String secretKey, String readerAccessKey, String readerSecretKey, Duration callTimeout) {
    public DownloadStorageProperties {
        region = region == null ? "us-east-1" : region;
        callTimeout = callTimeout == null ? Duration.ofSeconds(5) : callTimeout;
        if (enabled) {
            if (blank(region) || blank(bucket) || blank(accessKey) || blank(secretKey) || blank(readerAccessKey) || blank(readerSecretKey)
                    || accessKey.equals(readerAccessKey)
                    || callTimeout.isNegative() || callTimeout.isZero() || callTimeout.compareTo(Duration.ofSeconds(10)) > 0)
                throw new IllegalArgumentException("Explicit tag-only and download-reader credentials are required");
            if (!blank(endpoint)) {
                URI uri = URI.create(endpoint);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
                    throw new IllegalArgumentException("Invalid distribution storage endpoint");
            }
        }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}

package com.stove.download.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stove.download-storage")
public record ObjectStorageProperties(String endpoint, String presignEndpoint, String region,
        String accessKey, String secretKey, String bucket, Duration presignTtl, Duration callTimeout) {
    public ObjectStorageProperties {
        region = region == null ? "us-east-1" : region;
        presignTtl = presignTtl == null ? Duration.ofMinutes(5) : presignTtl;
        callTimeout = callTimeout == null ? Duration.ofSeconds(5) : callTimeout;
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()
                || bucket == null || bucket.isBlank() || region.isBlank())
            throw new IllegalArgumentException("Download requires explicit read-only storage credentials and a bucket");
        if (presignTtl.compareTo(Duration.ofSeconds(1)) < 0 || presignTtl.compareTo(Duration.ofMinutes(15)) > 0
                || callTimeout.isNegative() || callTimeout.isZero())
            throw new IllegalArgumentException("Invalid storage timeout or URL lifetime");
        requireEndpoint(endpoint);
        requireEndpoint(presignEndpoint);
    }

    private static void requireEndpoint(String value) {
        if (value == null || value.isBlank()) return;
        URI uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())))
            throw new IllegalArgumentException("Invalid storage endpoint");
    }
}

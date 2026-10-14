package com.stove.review.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "stove.review-storage")
public record ReviewStorageProperties(
        String endpoint,
        String presignEndpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        Duration presignTtl) {
    public ReviewStorageProperties {
        region = region == null ? "us-east-1" : region;
        bucket = bucket == null ? "stove-builds" : bucket;
        presignTtl = presignTtl == null ? Duration.ofMinutes(5) : presignTtl;
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank())
            throw new IllegalArgumentException(
                    "Review requires explicitly configured read-only storage credentials");
        if (bucket.isBlank()
                || presignTtl.compareTo(Duration.ofSeconds(1)) < 0
                || presignTtl.compareTo(Duration.ofMinutes(15)) > 0)
            throw new IllegalArgumentException(
                    "Review storage requires a bucket and URL lifetime of 1 second to 15 minutes");
    }

    public boolean pathStyleRequired() {
        return endpoint != null && !endpoint.isBlank();
    }
}

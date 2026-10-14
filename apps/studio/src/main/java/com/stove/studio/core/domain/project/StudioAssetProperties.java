package com.stove.studio.core.domain.project;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.asset")
public record StudioAssetProperties(String publicBaseUrl, Long maxThumbnailBytes) {
    public StudioAssetProperties {
        publicBaseUrl =
                publicBaseUrl == null
                        ? "http://localhost:8080"
                        : publicBaseUrl.replaceAll("/+$", "");
        maxThumbnailBytes = maxThumbnailBytes == null ? 5L * 1024 * 1024 : maxThumbnailBytes;
    }
}

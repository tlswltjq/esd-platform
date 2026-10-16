package com.stove.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.upload")
public record StudioUploadProperties(Long maxArtifactBytes) {
    public StudioUploadProperties {
        maxArtifactBytes = maxArtifactBytes == null ? 1073741824L : maxArtifactBytes;
    }
}

package com.stove.download.core.domain;

import java.net.URI;
import java.time.Instant;

public record SignedUrl(String url, Instant expiresAt) {
    public SignedUrl {
        if (url == null || expiresAt == null)
            throw new IllegalArgumentException("Invalid download URL");
        URI uri = URI.create(url);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Invalid download URL");
    }
}

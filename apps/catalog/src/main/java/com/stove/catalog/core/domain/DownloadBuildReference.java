package com.stove.catalog.core.domain;

public record DownloadBuildReference(long projectId, String storagePath) {
    public DownloadBuildReference {
        if (projectId <= 0 || storagePath == null || !storagePath.startsWith("s3://") || storagePath.length() > 2000)
            throw new IllegalArgumentException("Invalid download build reference");
    }
}

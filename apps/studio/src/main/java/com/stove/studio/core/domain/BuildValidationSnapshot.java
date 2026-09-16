package com.stove.studio.core.domain;

public record BuildValidationSnapshot(
        BuildStatus status,
        String storagePath,
        String expectedChecksum,
        long expectedSize,
        String productVersion
) {
}

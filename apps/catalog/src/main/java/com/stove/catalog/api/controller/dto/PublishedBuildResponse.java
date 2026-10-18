package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.PublishedBuild;

public record PublishedBuildResponse(Long productId, String productCode, Long releaseRequestId,
        long contentVersion, Long buildId, String version, long fileSize, String checksum, String storagePath) {
    public static PublishedBuildResponse from(PublishedBuild build) {
        return new PublishedBuildResponse(build.productId(), build.productCode(), build.releaseRequestId(),
                build.contentVersion(), build.buildId(), build.version(), build.fileSize(), build.checksum(), build.storagePath());
    }
}

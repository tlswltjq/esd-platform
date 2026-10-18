package com.stove.catalog.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record PublishedBuild(Long productId, String productCode, Long releaseRequestId,
        long contentVersion, Long buildId, String version, long fileSize, String checksum, String storagePath) {
    public PublishedBuild {
        requireIdentity(productId, productCode, releaseRequestId, contentVersion, buildId);
        requireFile(version, fileSize, checksum, storagePath);
    }

    private static void requireIdentity(Long productId, String code, Long releaseId, long contentVersion, Long buildId) {
        if (productId == null || productId <= 0 || code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")
                || releaseId == null || releaseId <= 0 || contentVersion <= 0 || buildId == null || buildId <= 0)
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
    }

    private static void requireFile(String version, long size, String checksum, String storagePath) {
        if (version == null || version.isBlank() || version.length() > 100 || size <= 0
                || checksum == null || !checksum.matches("[a-fA-F0-9]{64}")
                || storagePath == null || !storagePath.startsWith("s3://") || storagePath.length() > 2000)
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
    }
}

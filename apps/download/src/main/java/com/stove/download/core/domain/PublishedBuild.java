package com.stove.download.core.domain;

public record PublishedBuild(long productId, String productCode, long releaseRequestId,
        long contentVersion, long buildId, String version, long fileSize, String checksum,
        String storagePath) {
    public PublishedBuild {
        if (productId <= 0 || !DownloadValues.productCode(productCode)
                || releaseRequestId <= 0 || contentVersion <= 0 || buildId <= 0
                || !DownloadValues.buildVersion(version) || fileSize <= 0
                || !DownloadValues.checksum(checksum) || !DownloadValues.storageReference(storagePath))
            throw new IllegalArgumentException("Invalid published build");
    }
}

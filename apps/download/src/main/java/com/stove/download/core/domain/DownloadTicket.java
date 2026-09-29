package com.stove.download.core.domain;

import com.stove.common.event.payload.BuildVariant;
import java.time.Instant;

/** 소유 판정을 통과한 회원에게 발급되는 다운로드 티켓. */
public record DownloadTicket(
        String productCode,
        Long releaseId,
        Long buildId,
        String version,
        long fileSize,
        String checksum,
        String downloadUrl,
        Instant expiresAt,
        String platform,
        String architecture,
        String deltaFromVersion
) {
    public DownloadTicket(String productCode, Long releaseId, Long buildId, String version,
                          long fileSize, String checksum, String downloadUrl, Instant expiresAt) {
        this(productCode, releaseId, buildId, version, fileSize, checksum,
                downloadUrl, expiresAt, null, null, null);
    }

    public static DownloadTicket of(PatchManifest manifest, SignedUrl signed) {
        return new DownloadTicket(
                manifest.getProductCode(),
                manifest.getReleaseId(),
                manifest.getBuildId(),
                manifest.getVersion(),
                manifest.getFileSize(),
                manifest.getChecksum(),
                signed.url(),
                signed.expiresAt(), null, null, null);
    }

    public static DownloadTicket of(PatchManifest manifest, BuildVariant variant, SignedUrl signed) {
        return new DownloadTicket(manifest.getProductCode(), manifest.getReleaseId(),
                variant.buildId(), variant.version(), variant.fileSize(), variant.checksum(),
                signed.url(), signed.expiresAt(), variant.platform(), variant.architecture(),
                variant.deltaFromVersion());
    }
}

package com.stove.download.core.domain;

import java.time.Instant;

/** 소유 판정을 통과한 회원에게 발급되는 다운로드 티켓. */
public record DownloadTicket(
        String productCode,
        String version,
        long fileSize,
        String checksum,
        String downloadUrl,
        Instant expiresAt
) {
    public static DownloadTicket of(PatchManifest manifest, SignedUrl signed) {
        return new DownloadTicket(
                manifest.getProductCode(),
                manifest.getVersion(),
                manifest.getFileSize(),
                manifest.getChecksum(),
                signed.url(),
                signed.expiresAt());
    }
}

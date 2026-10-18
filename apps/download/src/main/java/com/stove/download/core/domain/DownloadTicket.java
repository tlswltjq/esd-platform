package com.stove.download.core.domain;

import java.time.Instant;

public record DownloadTicket(String productCode, String version, long fileSize, String checksum,
                             String downloadUrl, Instant expiresAt) {
    public static DownloadTicket of(PublishedBuild build, SignedUrl signed) {
        return new DownloadTicket(build.productCode(), build.version(), build.fileSize(),
                build.checksum(), signed.url(), signed.expiresAt());
    }
}

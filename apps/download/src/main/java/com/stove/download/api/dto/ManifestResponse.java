package com.stove.download.api.dto;

import com.stove.download.domain.PatchManifest;
import java.time.Instant;

public record ManifestResponse(
        String productCode,
        String version,
        long fileSize,
        String checksum,
        Instant releasedAt
) {
    public static ManifestResponse from(PatchManifest manifest) {
        return new ManifestResponse(manifest.getProductCode(), manifest.getVersion(),
                manifest.getFileSize(), manifest.getChecksum(), manifest.getReleasedAt());
    }
}

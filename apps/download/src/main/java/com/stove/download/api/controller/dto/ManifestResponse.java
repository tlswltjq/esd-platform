package com.stove.download.api.controller.dto;

import com.stove.download.core.domain.PatchManifest;
import com.stove.common.event.payload.BuildVariant;
import java.time.Instant;
import java.util.List;

public record ManifestResponse(
        String productCode,
        Long releaseId,
        Long buildId,
        Long metadataRevision,
        String version,
        long fileSize,
        String checksum,
        Instant releasedAt,
        List<ArtifactResponse> buildVariants
) {
    public static ManifestResponse from(PatchManifest manifest) {
        return new ManifestResponse(manifest.getProductCode(), manifest.getReleaseId(),
                manifest.getBuildId(), manifest.getMetadataRevision(), manifest.getVersion(),
                manifest.getFileSize(), manifest.getChecksum(), manifest.getReleasedAt(),
                manifest.getBuildVariants() == null ? List.of() : manifest.getBuildVariants().stream()
                        .map(ArtifactResponse::from).toList());
    }

    public record ArtifactResponse(Long buildId, String platform, String architecture,
                                   String version, long fileSize, String checksum,
                                   String deltaFromVersion) {
        public static ArtifactResponse from(BuildVariant variant) {
            return new ArtifactResponse(variant.buildId(), variant.platform(), variant.architecture(),
                    variant.version(), variant.fileSize(), variant.checksum(),
                    variant.deltaFromVersion());
        }
    }
}

package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.BuildStatus;
import java.time.Instant;

public record BuildResponse(
        Long buildId,
        Long gameId,
        String version,
        long fileSize,
        String checksum,
        String storagePath,
        String buildNumber,
        String platform,
        String architecture,
        BuildStatus status,
        String failureCode,
        Instant createdAt,
        Long duplicateOfBuildId
) {
    public static BuildResponse from(GameBuild build) {
        return new BuildResponse(build.getId(), build.getGameId(), build.getVersion(),
                build.getFileSize(), build.getChecksum(), build.getStoragePath(), build.getBuildNumber(),
                build.getPlatform(), build.getArchitecture(), build.getStatus(), build.getFailureCode(),
                build.getCreatedAt(), build.getDuplicateOfBuildId());
    }
}

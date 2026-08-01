package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.GameBuild;
import java.time.Instant;

public record BuildResponse(
        Long buildId,
        Long gameId,
        String version,
        long fileSize,
        String checksum,
        String storagePath,
        Instant createdAt
) {
    public static BuildResponse from(GameBuild build) {
        return new BuildResponse(build.getId(), build.getGameId(), build.getVersion(),
                build.getFileSize(), build.getChecksum(), build.getStoragePath(), build.getCreatedAt());
    }
}

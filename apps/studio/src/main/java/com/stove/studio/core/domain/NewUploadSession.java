package com.stove.studio.core.domain;

public record NewUploadSession(
        String productVersion,
        String buildNumber,
        String platform,
        String architecture,
        String fileName,
        long fileSize,
        String sha256,
        String commitSha,
        String repository,
        String ciProvider,
        String ciRunId,
        String idempotencyKey
) {
}

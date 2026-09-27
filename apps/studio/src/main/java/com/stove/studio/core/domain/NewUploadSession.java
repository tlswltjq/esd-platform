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
        String sourceRef,
        String ciProvider,
        String ciRunId,
        String idempotencyKey,
        String deltaFromVersion
) {
    public NewUploadSession(String productVersion, String buildNumber, String platform,
                            String architecture, String fileName, long fileSize, String sha256,
                            String commitSha, String repository, String sourceRef,
                            String ciProvider, String ciRunId, String idempotencyKey) {
        this(productVersion, buildNumber, platform, architecture, fileName, fileSize, sha256,
                commitSha, repository, sourceRef, ciProvider, ciRunId, idempotencyKey, null);
    }
}

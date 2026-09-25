package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.NewUploadSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUploadSessionRequest(
        @NotBlank @Size(max = 30) String productVersion,
        @NotBlank @Size(max = 100) String buildNumber,
        @NotBlank @Size(max = 30) String platform,
        @NotBlank @Size(max = 30) String architecture,
        @NotBlank @Size(max = 200) String fileName,
        @Positive long fileSize,
        @NotBlank @Pattern(regexp = "(?i)(sha256:)?[a-f0-9]{64}") String sha256,
        @Size(max = 64) String commitSha,
        @Size(max = 300) String repository,
        @Size(max = 300) String sourceRef,
        @Size(max = 30) String ciProvider,
        @Size(max = 100) String ciRunId,
        @NotBlank @Size(max = 100) String idempotencyKey
) {
    public NewUploadSession toCommand() {
        return new NewUploadSession(productVersion, buildNumber, platform, architecture, fileName,
                fileSize, sha256.replaceFirst("(?i)^sha256:", "").toLowerCase(), commitSha,
                repository, sourceRef, ciProvider, ciRunId, idempotencyKey);
    }
}

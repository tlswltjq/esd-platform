package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.NewBuild;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record UploadBuildRequest(
        @NotBlank String version,
        @Positive long fileSize,
        @NotBlank String checksum
) {
    public NewBuild toCommand() {
        return new NewBuild(version, fileSize, checksum);
    }
}

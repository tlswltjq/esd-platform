package com.stove.studio.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record UploadBuildRequest(
        @NotBlank String version,
        @Positive long fileSize,
        @NotBlank String checksum
) {
}

package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCiTrustPolicyRequest(
        @NotBlank @Size(max = 30) String provider,
        @NotBlank @Size(max = 300) String repository,
        @NotBlank @Size(max = 300) String refPattern,
        @NotBlank @Size(max = 30) String platform,
        @Size(max = 300) String protectedRefPattern,
        @Size(max = 100) String requiredEnvironment) {
}

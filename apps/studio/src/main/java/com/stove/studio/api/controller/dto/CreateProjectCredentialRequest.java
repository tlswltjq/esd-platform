package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateProjectCredentialRequest(
        @NotBlank @Size(max = 100) String name,
        Instant expiresAt
) {
}

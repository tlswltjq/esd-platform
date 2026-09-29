package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.ProjectCredential;
import java.time.Instant;

public record ProjectCredentialResponse(
        Long id,
        Long gameId,
        String name,
        String tokenPrefix,
        String status,
        Instant expiresAt,
        Instant lastUsedAt
) {
    public static ProjectCredentialResponse from(ProjectCredential credential) {
        return new ProjectCredentialResponse(credential.getId(), credential.getGameId(),
                credential.getName(), credential.getTokenPrefix(), credential.getStatus().name(),
                credential.getExpiresAt(), credential.getLastUsedAt());
    }
}

package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.IssuedProjectCredential;
import java.time.Instant;

/** token은 생성 응답에서 한 번만 노출한다. */
public record IssuedProjectCredentialResponse(
        Long id,
        Long gameId,
        String name,
        String tokenPrefix,
        String token,
        Instant expiresAt
) {
    public static IssuedProjectCredentialResponse from(IssuedProjectCredential issued) {
        return new IssuedProjectCredentialResponse(issued.credential().getId(),
                issued.credential().getGameId(), issued.credential().getName(),
                issued.credential().getTokenPrefix(), issued.token(), issued.credential().getExpiresAt());
    }
}

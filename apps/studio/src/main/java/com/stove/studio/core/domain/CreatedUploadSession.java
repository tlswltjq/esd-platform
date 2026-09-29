package com.stove.studio.core.domain;

import java.time.Instant;
import java.util.List;

public record CreatedUploadSession(
        Long sessionId,
        Long buildId,
        long partSize,
        List<UploadPartUrl> parts,
        Instant expiresAt
) {
}

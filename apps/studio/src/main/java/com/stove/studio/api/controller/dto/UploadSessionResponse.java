package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.CreatedUploadSession;
import java.time.Instant;
import java.util.List;

public record UploadSessionResponse(
        Long uploadSessionId,
        Long buildId,
        long partSize,
        List<UploadPartResponse> parts,
        Instant expiresAt
) {
    public static UploadSessionResponse from(CreatedUploadSession session) {
        return new UploadSessionResponse(session.sessionId(), session.buildId(), session.partSize(),
                session.parts().stream().map(UploadPartResponse::from).toList(), session.expiresAt());
    }
}

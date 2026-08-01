package com.stove.download.api.dto;

import java.time.Instant;

public record DownloadTicketResponse(
        String productCode,
        String version,
        long fileSize,
        String checksum,
        String downloadUrl,
        Instant expiresAt
) {
}

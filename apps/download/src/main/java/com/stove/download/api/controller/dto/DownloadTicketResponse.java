package com.stove.download.api.controller.dto;

import com.stove.download.core.domain.DownloadTicket;
import java.time.Instant;

public record DownloadTicketResponse(
        String productCode,
        String version,
        long fileSize,
        String checksum,
        String downloadUrl,
        Instant expiresAt
) {
    public static DownloadTicketResponse from(DownloadTicket ticket) {
        return new DownloadTicketResponse(ticket.productCode(), ticket.version(), ticket.fileSize(),
                ticket.checksum(), ticket.downloadUrl(), ticket.expiresAt());
    }
}

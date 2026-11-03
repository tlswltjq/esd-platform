package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.CatalogPublicationJob;
import java.time.Instant;

public record PublicationJobResponse(Long requestId, String sourceEventId, Long projectId,
        Long reviewRequestId, long contentVersion, String productCode, String state,
        int attemptCount, Instant nextAttemptAt, String lastError, Instant createdAt) {
    public static PublicationJobResponse from(CatalogPublicationJob job) {
        return new PublicationJobResponse(job.getRequestId(), job.getSourceEventId(), job.getProjectId(),
                job.getReviewRequestId(), job.getContentVersion(), job.getProductCode(), job.getState(),
                job.getAttemptCount(), job.getNextAttemptAt(), job.getLastError(), job.getCreatedAt());
    }
}

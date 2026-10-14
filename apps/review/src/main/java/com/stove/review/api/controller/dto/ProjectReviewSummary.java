package com.stove.review.api.controller.dto;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.review.core.domain.*;

import java.time.Instant;

public record ProjectReviewSummary(
        Long reviewRequestId,
        Long projectId,
        String productCode,
        long contentVersion,
        String title,
        ReviewStatus status,
        String actor,
        Instant requestedAt,
        Instant decidedAt) {
    public static ProjectReviewSummary from(ProjectReview r, ProjectSnapshot s) {
        return new ProjectReviewSummary(
                r.getReviewRequestId(),
                r.getProjectId(),
                r.getProductCode(),
                r.getContentVersion(),
                s.title(),
                r.getStatus(),
                r.getActor(),
                r.getRequestedAt(),
                r.getDecidedAt());
    }
}

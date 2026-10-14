package com.stove.review.api.controller.dto;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.review.core.domain.*;

import java.time.Instant;

public record ProjectReviewResponse(
        Long reviewRequestId,
        ReviewSnapshotResponse snapshot,
        ReviewStatus status,
        String feedback,
        String actor,
        Instant decidedAt,
        Instant requestedAt,
        Instant createdAt) {
    public static ProjectReviewResponse from(ProjectReview r, ProjectSnapshot s) {
        return new ProjectReviewResponse(
                r.getReviewRequestId(),
                ReviewSnapshotResponse.from(s),
                r.getStatus(),
                r.getFeedback(),
                r.getActor(),
                r.getDecidedAt(),
                r.getRequestedAt(),
                r.getCreatedAt());
    }
}

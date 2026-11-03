package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.StorePage;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.review.ProjectReviewRequest;

public record ProjectResponse(
        Long id,
        String productCode,
        StorePage storePage,
        Long currentBuildId,
        long contentVersion,
        ProjectReviewRequest currentReviewRequest,
        ProjectReleaseRequest currentReleaseRequest,
        boolean releaseEligible,
        ProjectReviewRequest activeReviewRequest,
        ProjectReleaseRequest activeReleaseRequest) {
    public static ProjectResponse from(
            Project p, ProjectReviewRequest review, ProjectReleaseRequest release,
            ProjectReviewRequest activeReview, ProjectReleaseRequest activeRelease) {
        return new ProjectResponse(
                p.getId(),
                p.getProductCode(),
                p.storePage(),
                p.getCurrentBuildId(),
                p.getContentVersion(),
                review,
                release,
                review != null && review.approvedFor(p.getContentVersion()), activeReview, activeRelease);
    }
}

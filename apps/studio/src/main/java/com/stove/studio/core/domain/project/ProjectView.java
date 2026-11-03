package com.stove.studio.core.domain.project;

import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.review.ProjectReviewRequest;

public record ProjectView(Project project, ProjectReviewRequest currentReview,
        ProjectReleaseRequest currentRelease, ProjectReviewRequest activeReview,
        ProjectReleaseRequest activeRelease) {}

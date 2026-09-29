package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.Release;
import com.stove.studio.core.domain.ReleaseStatus;
import com.stove.studio.core.domain.ReleaseChannel;
import com.stove.studio.core.domain.ReleaseChangeType;
import com.stove.studio.core.domain.SmokeTestStatus;
import java.time.Instant;

public record ReleaseResponse(Long releaseId, Long submissionId, Long buildId,
                              ReleaseChannel channel, ReleaseStatus status, Instant publishAt, Instant publishedAt,
                              Long previousReleaseId, String publishTimeZone, ReleaseChangeType changeType,
                              SmokeTestStatus smokeTestStatus, Instant smokeTestedAt, String smokeTestFailure) {
    public static ReleaseResponse from(Release release) {
        return new ReleaseResponse(release.getId(), release.getSubmissionId(), release.getBuildId(),
                release.getChannel(), release.getStatus(), release.getPublishAt(), release.getPublishedAt(),
                release.getPreviousReleaseId(), release.getPublishTimeZone(), release.getChangeType(),
                release.getSmokeTestStatus(), release.getSmokeTestedAt(), release.getSmokeTestFailure());
    }
}

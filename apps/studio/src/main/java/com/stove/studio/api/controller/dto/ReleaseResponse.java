package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.release.Release;
import com.stove.studio.core.domain.release.ReleaseChangeType;
import com.stove.studio.core.domain.release.ReleaseChannel;
import com.stove.studio.core.domain.release.ReleaseStatus;
import com.stove.studio.core.domain.release.SmokeTestStatus;
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

package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.submission.Submission;
import com.stove.studio.core.domain.submission.SubmissionStatus;

public record SubmissionResponse(
        Long submissionId,
        Long gameId,
        int sequenceNo,
        Long metadataRevisionId,
        Long pricingRevisionId,
        Long ratingRevisionId,
        Long buildId,
        SubmissionStatus status
) {
    public static SubmissionResponse from(Submission submission) {
        return new SubmissionResponse(submission.getId(), submission.getGameId(), submission.getSequenceNo(),
                submission.getMetadataRevisionId(), submission.getPricingRevisionId(),
                submission.getRatingRevisionId(), submission.getBuildId(), submission.getStatus());
    }
}

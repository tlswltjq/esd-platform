package com.stove.review.api.controller.dto;

import com.stove.review.core.domain.ReviewCase;
import com.stove.review.core.domain.ReviewCaseStatus;
import com.stove.review.core.domain.ReviewType;

public record ReviewCaseResponse(Long reviewCaseId, Long submissionId, ReviewType reviewType,
                                 ReviewCaseStatus status, String reasonCode, String externalFeedback,
                                 String ratingCode) {
    public static ReviewCaseResponse from(ReviewCase value) {
        return new ReviewCaseResponse(value.getId(), value.getSubmissionId(), value.getReviewType(),
                value.getStatus(), value.getReasonCode(), value.getExternalFeedback(), value.getRatingCode());
    }
}

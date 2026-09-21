package com.stove.review.api.controller.dto;

import com.stove.review.core.domain.ReviewCase;
import com.stove.review.core.domain.ReviewCaseStatus;
import com.stove.review.core.domain.ReviewType;

public record ReviewCaseResponse(Long reviewCaseId, Long submissionId, ReviewType reviewType,
                                 ReviewCaseStatus status, String reasonCode, String externalFeedback,
                                 String ratingPath, String targetRatingCode, String externalSubmissionId,
                                 String ratingCode, String certificationNumber, String issuer,
                                 java.time.Instant issuedAt, String country) {
    public static ReviewCaseResponse from(ReviewCase value) {
        return new ReviewCaseResponse(value.getId(), value.getSubmissionId(), value.getReviewType(),
                value.getStatus(), value.getReasonCode(), value.getExternalFeedback(), value.getRatingPath(),
                value.getTargetRatingCode(), value.getExternalSubmissionId(), value.getRatingCode(),
                value.getCertificationNumber(), value.getIssuer(), value.getIssuedAt(), value.getCountry());
    }
}

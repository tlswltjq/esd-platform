package com.stove.review.api.controller.dto;

import com.stove.review.core.domain.ReviewCase;
import com.stove.review.core.domain.ReviewCaseStatus;
import com.stove.review.core.domain.ReviewType;
import java.time.Instant;

public record ReviewCaseResponse(Long reviewCaseId, Long submissionId, ReviewType reviewType,
                                 ReviewCaseStatus status, String reasonCode, String externalFeedback,
                                 String ratingPath, String recommendedRatingCode,
                                 String ratingCode, String certificationNumber, String issuer,
                                 Instant issuedAt, String country, String externalApplicationNumber,
                                 Instant externalSubmittedAt, String externalEvidenceUrl) {
    public static ReviewCaseResponse from(ReviewCase value) {
        return new ReviewCaseResponse(value.getId(), value.getSubmissionId(), value.getReviewType(),
                value.getStatus(), value.getReasonCode(), value.getExternalFeedback(), value.getRatingPath(),
                value.getRecommendedRatingCode(), value.getRatingCode(), value.getCertificationNumber(),
                value.getIssuer(), value.getIssuedAt(), value.getCountry(),
                value.getExternalApplicationNumber(), value.getExternalSubmittedAt(),
                value.getExternalEvidenceUrl());
    }
}

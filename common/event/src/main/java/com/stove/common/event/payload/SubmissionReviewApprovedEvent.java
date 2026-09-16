package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

public record SubmissionReviewApprovedEvent(
        String eventId, Instant occurredAt,
        Long reviewId, Long submissionId, Long buildId,
        Long metadataRevision, Long pricingRevision, Long ratingRevision,
        String reviewType, String productCode,
        String ratingCode, String certificationNumber, String issuer,
        Instant issuedAt, String country
) implements DomainEvent {
    public static SubmissionReviewApprovedEvent of(
            Long reviewId, Long submissionId, Long buildId,
            Long metadataRevision, Long pricingRevision, Long ratingRevision,
            String reviewType, String productCode,
            String ratingCode, String certificationNumber, String issuer,
            Instant issuedAt, String country) {
        return new SubmissionReviewApprovedEvent(UUID.randomUUID().toString(), Instant.now(),
                reviewId, submissionId, buildId, metadataRevision, pricingRevision, ratingRevision,
                reviewType, productCode, ratingCode, certificationNumber, issuer, issuedAt, country);
    }
    @Override public String eventType() { return EventType.SUBMISSION_REVIEW_APPROVED; }
    @Override public String topic() { return Topics.REVIEW; }
    @Override public String partitionKey() { return productCode; }
}

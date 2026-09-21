package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

public record SubmissionCreatedEvent(
        String eventId, Instant occurredAt,
        Long submissionId, Long gameId, String productCode, Long sellerId,
        Long metadataRevision, Long pricingRevision, Long ratingRevision, Long buildId,
        String title, String shortDescription, long price, String currency,
        String ratingPath, String ratingPolicyVersion, String ratingCountry,
        String targetRatingCode, String ratingQuestionnaire, String productVersion
) implements DomainEvent {
    public static SubmissionCreatedEvent of(Long submissionId, Long gameId, String productCode, Long sellerId,
                                            Long metadataRevision, Long pricingRevision, Long ratingRevision,
                                            Long buildId, String title, String shortDescription, long price,
                                            String currency, String ratingPath, String ratingPolicyVersion,
                                            String ratingCountry, String targetRatingCode,
                                            String ratingQuestionnaire, String productVersion) {
        return new SubmissionCreatedEvent(UUID.randomUUID().toString(), Instant.now(), submissionId, gameId,
                productCode, sellerId, metadataRevision, pricingRevision, ratingRevision, buildId,
                title, shortDescription, price, currency, ratingPath, ratingPolicyVersion, ratingCountry,
                targetRatingCode, ratingQuestionnaire, productVersion);
    }
    @Override public String eventType() { return EventType.SUBMISSION_CREATED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}

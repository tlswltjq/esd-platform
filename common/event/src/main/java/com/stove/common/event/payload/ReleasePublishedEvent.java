package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

public record ReleasePublishedEvent(
        String eventId, Instant occurredAt,
        Long releaseId, Long previousReleaseId, Long submissionId,
        Long gameId, String productCode, Long sellerId,
        Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
        String title, String shortDescription, long price, String currency,
        String ratingCode, String productVersion,
        long fileSize, String checksum, String storagePath, String channel
) implements DomainEvent {
    public static ReleasePublishedEvent of(
            Long releaseId, Long previousReleaseId, Long submissionId,
            Long gameId, String productCode, Long sellerId,
            Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
            String title, String shortDescription, long price, String currency,
            String ratingCode, String productVersion, long fileSize, String checksum, String storagePath) {
        return new ReleasePublishedEvent(UUID.randomUUID().toString(), Instant.now(), releaseId,
                previousReleaseId, submissionId, gameId, productCode, sellerId, buildId,
                metadataRevision, pricingRevision, ratingRevision, title, shortDescription, price,
                currency, ratingCode, productVersion, fileSize, checksum, storagePath, "LIVE");
    }
    @Override public String eventType() { return EventType.RELEASE_PUBLISHED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}

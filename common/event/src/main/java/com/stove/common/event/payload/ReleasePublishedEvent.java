package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReleasePublishedEvent(
        String eventId, Instant occurredAt,
        Long releaseId, Long previousReleaseId, Long submissionId,
        Long gameId, String productCode, Long sellerId,
        Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
        String title, String shortDescription, long price, String currency,
        String ratingCode, String productVersion,
        long fileSize, String checksum, String storagePath, String channel,
        String productKind, String parentProductCode, String editionName,
        List<String> bundleProductCodes,
        List<BuildVariant> buildVariants
) implements DomainEvent {
    public static ReleasePublishedEvent of(
            Long releaseId, Long previousReleaseId, Long submissionId,
            Long gameId, String productCode, Long sellerId,
            Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
            String title, String shortDescription, long price, String currency,
            String ratingCode, String productVersion, long fileSize, String checksum, String storagePath) {
        return of(releaseId, previousReleaseId, submissionId, gameId, productCode, sellerId,
                buildId, metadataRevision, pricingRevision, ratingRevision, title, shortDescription,
                price, currency, ratingCode, productVersion, fileSize, checksum, storagePath,
                "BASIC", null, null, List.of(), List.of());
    }

    public static ReleasePublishedEvent of(
            Long releaseId, Long previousReleaseId, Long submissionId,
            Long gameId, String productCode, Long sellerId,
            Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
            String title, String shortDescription, long price, String currency,
            String ratingCode, String productVersion, long fileSize, String checksum, String storagePath,
            String productKind, String parentProductCode, String editionName,
            List<String> bundleProductCodes) {
        return of(releaseId, previousReleaseId, submissionId, gameId, productCode, sellerId,
                buildId, metadataRevision, pricingRevision, ratingRevision, title, shortDescription,
                price, currency, ratingCode, productVersion, fileSize, checksum, storagePath,
                productKind, parentProductCode, editionName, bundleProductCodes, List.of());
    }

    public static ReleasePublishedEvent of(
            Long releaseId, Long previousReleaseId, Long submissionId,
            Long gameId, String productCode, Long sellerId,
            Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
            String title, String shortDescription, long price, String currency,
            String ratingCode, String productVersion, long fileSize, String checksum, String storagePath,
            String productKind, String parentProductCode, String editionName,
            List<String> bundleProductCodes, List<BuildVariant> buildVariants) {
        return new ReleasePublishedEvent(UUID.randomUUID().toString(), Instant.now(), releaseId,
                previousReleaseId, submissionId, gameId, productCode, sellerId, buildId,
                metadataRevision, pricingRevision, ratingRevision, title, shortDescription, price,
                currency, ratingCode, productVersion, fileSize, checksum, storagePath, "LIVE",
                productKind, parentProductCode, editionName, List.copyOf(bundleProductCodes),
                List.copyOf(buildVariants));
    }
    @Override public String eventType() { return EventType.RELEASE_PUBLISHED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}

package com.stove.catalog.core.domain;

import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "catalog_release")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogRelease {
    @Id private Long releaseRequestId;

    @Column(nullable = false, length = 64)
    private String requestEventId;

    @Column(nullable = false)
    private Long reviewRequestId;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 50)
    private String productCode;

    @Column(nullable = false)
    private long contentVersion;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String snapshot;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 2000)
    private String reason;

    @Column(nullable = false)
    private Instant requestedAt;

    @Column(nullable = false)
    private Instant processedAt;

    public static CatalogRelease decided(
            ProjectReleaseRequestedEvent event, String json, boolean stale, Instant now) {
        var release = new CatalogRelease();
        release.releaseRequestId = event.releaseRequestId();
        release.requestEventId = event.eventId();
        release.reviewRequestId = event.reviewRequestId();
        release.projectId = event.snapshot().projectId();
        release.productCode = event.snapshot().productCode();
        release.contentVersion = event.snapshot().contentVersion();
        release.snapshot = json;
        release.status = stale ? "REJECTED" : "COMPLETED";
        release.reason = stale ? "STALE_CONTENT_VERSION" : null;
        release.requestedAt = event.occurredAt();
        release.processedAt = now;
        return release;
    }
}

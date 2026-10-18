package com.stove.catalog.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
            ReleaseSubmission submission, String json, boolean stale, Instant now) {
        CatalogRelease release = new CatalogRelease();
        release.releaseRequestId = submission.releaseRequestId();
        release.requestEventId = submission.eventId();
        release.reviewRequestId = submission.reviewRequestId();
        release.projectId = submission.snapshot().projectId();
        release.productCode = submission.snapshot().productCode();
        release.contentVersion = submission.snapshot().contentVersion();
        release.snapshot = json;
        release.status = stale ? "REJECTED" : "COMPLETED";
        release.reason = stale ? "STALE_CONTENT_VERSION" : null;
        release.requestedAt = submission.occurredAt();
        release.processedAt = now;
        return release;
    }
}

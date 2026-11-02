package com.stove.catalog.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "catalog_download_change")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogDownloadChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long productId;
    @Column(nullable = false, length = 100) private String actorSubject;
    @Column(nullable = false) private boolean previousRestricted;
    @Column(nullable = false) private boolean restricted;
    @Column(nullable = false) private long restrictionVersion;
    @Column(nullable = false, length = 500) private String reason;
    @Column(nullable = false) private Instant changedAt;

    public static CatalogDownloadChange record(CatalogProduct product, String actor, boolean before, String reason, Instant now) {
        CatalogDownloadChange change = new CatalogDownloadChange();
        change.productId = product.getId();
        change.actorSubject = actor;
        change.previousRestricted = before;
        change.restricted = product.isDownloadRestricted();
        change.restrictionVersion = product.getDownloadRestrictionVersion();
        change.reason = reason;
        change.changedAt = now;
        return change;
    }
}

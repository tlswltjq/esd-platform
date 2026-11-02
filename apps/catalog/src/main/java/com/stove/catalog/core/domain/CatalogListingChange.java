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
@Table(name = "catalog_listing_change")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogListingChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long productId;
    @Column(nullable = false, length = 100) private String actorSubject;
    @Column(nullable = false, length = 20) private String authority;
    @Column(nullable = false, length = 20) private String previousPreference;
    @Column(nullable = false, length = 20) private String listingPreference;
    @Column(nullable = false) private boolean previousPlatformHidden;
    @Column(nullable = false) private boolean platformHidden;
    @Column(nullable = false) private long previousVersion;
    @Column(nullable = false) private long catalogVersion;
    @Column(nullable = false) private Instant changedAt;

    public static CatalogListingChange record(CatalogProduct product, String actor, String authority,
            ListingPreference before, boolean hidden, long version, Instant now) {
        CatalogListingChange result = new CatalogListingChange();
        result.productId = product.getId(); result.actorSubject = actor; result.authority = authority;
        result.previousPreference = before.name(); result.listingPreference = product.getListingPreference().name();
        result.previousPlatformHidden = hidden; result.platformHidden = product.isPlatformListingHidden();
        result.previousVersion = version; result.catalogVersion = product.getCatalogVersion(); result.changedAt = now;
        return result;
    }
}

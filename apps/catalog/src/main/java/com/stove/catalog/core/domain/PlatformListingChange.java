package com.stove.catalog.core.domain;

public record PlatformListingChange(String productCode, Boolean hidden, long expectedCatalogVersion) {
    public PlatformListingChange {
        ListingValues.requireProductCode(productCode);
        if (hidden == null || expectedCatalogVersion <= 0) throw new IllegalArgumentException("Invalid platform listing change");
    }
}

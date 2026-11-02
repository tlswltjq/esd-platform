package com.stove.catalog.core.domain;

public record ListingChange(String productCode, ListingPreference preference, long expectedCatalogVersion) {
    public ListingChange {
        ListingValues.requireProductCode(productCode);
        if (preference == null || expectedCatalogVersion <= 0) throw new IllegalArgumentException("Invalid listing change");
    }
}

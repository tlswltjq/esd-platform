package com.stove.catalog.core.domain;

public record CatalogListingView(String productCode, ListingPreference listingPreference,
        boolean platformHidden, boolean listed, String saleStatus, long catalogVersion) {
    public static CatalogListingView from(CatalogProduct p) {
        return new CatalogListingView(p.getProductCode(), p.getListingPreference(), p.isPlatformListingHidden(),
                p.isListed(), p.getSaleStatus(), p.getCatalogVersion());
    }
}

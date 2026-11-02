package com.stove.catalog.core.domain;

import java.time.Instant;

public record CatalogSalesView(String productCode, String sellerSaleStatus, boolean platformRestricted,
        String saleStatus, long catalogVersion, Instant updatedAt) {
    public static CatalogSalesView from(CatalogProduct p) {
        return new CatalogSalesView(p.getProductCode(), p.getSellerSaleStatus(), p.isPlatformRestricted(),
                p.getSaleStatus(), p.getCatalogVersion(), p.getUpdatedAt());
    }
}

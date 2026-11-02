package com.stove.catalog.core.domain;

public enum ListingPreference {
    AUTO, LISTED, UNLISTED;

    public boolean isListed(String saleStatus) {
        if (!"ON_SALE".equals(saleStatus) && !"STOPPED".equals(saleStatus) && !"BLOCKED".equals(saleStatus))
            throw new IllegalArgumentException("Unknown sale status");
        return this == LISTED || (this == AUTO && "ON_SALE".equals(saleStatus));
    }
}

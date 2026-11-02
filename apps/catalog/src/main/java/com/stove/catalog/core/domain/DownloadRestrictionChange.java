package com.stove.catalog.core.domain;

public record DownloadRestrictionChange(String productCode, Boolean restricted, long expectedRestrictionVersion, String reason) {
    public DownloadRestrictionChange {
        if (productCode == null || !productCode.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")
                || restricted == null || expectedRestrictionVersion <= 0
                || reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("Invalid download restriction change");
    }
}

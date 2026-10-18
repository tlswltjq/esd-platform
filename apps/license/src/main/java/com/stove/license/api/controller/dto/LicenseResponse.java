package com.stove.license.api.controller.dto;

import com.stove.license.core.domain.License;

import java.time.Instant;

public record LicenseResponse(String licenseId, Long productId, String productCode, String title, Instant acquiredAt) {
    public static LicenseResponse from(License license) {
        return new LicenseResponse(license.getLicenseId(), license.getProductId(), license.getProductCode(),
                license.getTitle(), license.getAcquiredAt());
    }
}

package com.stove.license.api.dto;

import com.stove.license.domain.License;
import com.stove.license.domain.LicenseStatus;
import java.time.Instant;

public record LicenseResponse(
        Long licenseId,
        String orderNo,
        Long productId,
        String licenseKey,
        LicenseStatus status,
        Instant issuedAt
) {
    public static LicenseResponse from(License license) {
        return new LicenseResponse(
                license.getId(),
                license.getOrderNo(),
                license.getProductId(),
                license.getLicenseKey(),
                license.getStatus(),
                license.getIssuedAt());
    }
}

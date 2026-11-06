package com.stove.license.api.controller.dto;

import com.stove.license.core.domain.LicenseRegistration;
import com.stove.license.core.domain.LicenseStatus;
import java.time.Instant;

/** 라이브러리의 보유권 한 개. licenseId는 구매 근거(주문 1건)의 식별자다. */
public record LicenseResponse(String licenseId, Long productId, String productCode, String title,
        Instant acquiredAt, LicenseStatus status) {
    public static LicenseResponse from(LicenseRegistration registration) {
        return new LicenseResponse(registration.getLicenseId(), registration.getProductId(), registration.getProductCode(),
                registration.getTitle(), registration.getRegisteredAt(), registration.getStatus());
    }
}

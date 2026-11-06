package com.stove.license.core.domain;

import java.util.List;

/** 보유권 조회에 쓰는 상태 묶음. */
public final class LicenseRegistrations {
    /** 라이브러리에 보이는 상태. ENDED는 주문 내역에만 남긴다(결정 97). */
    public static final List<LicenseStatus> OPEN = List.of(LicenseStatus.ACTIVE, LicenseStatus.SUSPENDED);

    private LicenseRegistrations() {
    }

    public static LicenseRegistration current(LicenseRegistrationRepository registrations, License purchase) {
        return registrations.findFirstByLicenseIdAndStatusIn(purchase.getLicenseId(), OPEN).orElse(null);
    }

    public static boolean activeExists(LicenseRegistrationRepository registrations, License purchase) {
        return registrations.existsByBuyerSubjectAndProductIdAndStatus(purchase.getBuyerSubject(), purchase.getProductId(),
                LicenseStatus.ACTIVE);
    }
}

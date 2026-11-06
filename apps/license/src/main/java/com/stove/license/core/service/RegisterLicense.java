package com.stove.license.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRegistration;
import com.stove.license.core.domain.LicenseRegistrationRepository;
import com.stove.license.core.domain.LicenseRegistrations;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import com.stove.license.core.domain.LicenseValues;
import com.stove.license.core.domain.PurchaseStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 보관함 항목을 라이브러리에 등록한다(결정 93). 같은 고객·상품의 구매 근거를 잠근 뒤 쓸 수 있는 보유권 유무를 판단한다. */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RegisterLicense {
    private final LicenseRepository licenses;
    private final LicenseRegistrationRepository registrations;
    private final EntityManager entityManager;

    public LicenseRegistration execute(String buyer, String licenseId) {
        if (!LicenseValues.licenseId(licenseId)) throw new BusinessException(ErrorCode.INVALID_REQUEST, "보관함 항목 식별자가 올바르지 않습니다.");
        License purchase = ProductPurchaseLock.lockOwned(licenses, buyer, licenseId);
        LicenseRegistration current = LicenseRegistrations.current(registrations, purchase);
        if (purchase.getStatus() == PurchaseStatus.REGISTERED && current != null && current.getStatus() == LicenseStatus.ACTIVE)
            return current;
        LicenseRegistration registration = purchase.register(LicenseRegistrations.activeExists(registrations, purchase),
                Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS));
        registrations.save(registration);
        entityManager.flush();
        return registration;
    }
}

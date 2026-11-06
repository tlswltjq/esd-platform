package com.stove.license.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.LicenseRegistration;
import com.stove.license.core.domain.LicenseRegistrationRepository;
import com.stove.license.core.domain.LicenseRegistrations;
import com.stove.license.core.domain.LicenseStatus;
import com.stove.license.core.domain.LicenseValues;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadLicense {
    private final LicenseRegistrationRepository registrations;

    /** 상품 하나의 현재 보유권. 쓸 수 있는 것이 있으면 그것, 없으면 가장 최근 정지된 것. Download가 ACTIVE를 확인한다. */
    public LicenseRegistration execute(String buyer, String productCode) {
        if (!LicenseValues.productCode(productCode))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "상품 코드가 올바르지 않습니다.");
        List<LicenseRegistration> open = registrations.findByBuyerSubjectAndProductCodeAndStatusInOrderByIdDesc(buyer, productCode,
                LicenseRegistrations.OPEN);
        for (LicenseRegistration registration : open) {
            if (registration.getStatus() == LicenseStatus.ACTIVE) return registration;
        }
        if (open.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return open.getFirst();
    }
}

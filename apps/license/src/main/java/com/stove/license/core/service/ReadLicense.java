package com.stove.license.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseValues;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadLicense {
    private final LicenseRepository licenses;

    public License execute(String buyer, String productCode) {
        if (!LicenseValues.productCode(productCode))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "상품 코드가 올바르지 않습니다.");
        return licenses.findByBuyerSubjectAndProductCode(buyer, productCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}

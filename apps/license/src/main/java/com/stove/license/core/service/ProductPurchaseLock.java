package com.stove.license.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import java.util.List;

/** 같은 고객·상품의 구매 근거 전체를 id 순서로 잠그고 대상 구매 근거를 돌려준다. */
final class ProductPurchaseLock {
    private ProductPurchaseLock() {
    }

    static License lock(LicenseRepository licenses, String orderNo) {
        License found = licenses.findByOrderNo(orderNo).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return pick(licenses.lockProductPurchases(found.getBuyerSubject(), found.getProductId()), found.getLicenseId());
    }

    static License lockOwned(LicenseRepository licenses, String buyer, String licenseId) {
        License found = licenses.findByBuyerSubjectAndLicenseId(buyer, licenseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return pick(licenses.lockProductPurchases(found.getBuyerSubject(), found.getProductId()), found.getLicenseId());
    }

    private static License pick(List<License> locked, String licenseId) {
        for (License purchase : locked) {
            if (purchase.getLicenseId().equals(licenseId)) return purchase;
        }
        throw new BusinessException(ErrorCode.CONFLICT, "잠근 구매 근거가 대상과 다릅니다.");
    }
}

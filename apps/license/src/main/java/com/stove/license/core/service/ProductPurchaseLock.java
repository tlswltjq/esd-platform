package com.stove.license.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;

/**
 * 같은 고객·상품의 구매 근거 전체를 id 순서로 잠그고 대상 구매 근거를 돌려준다.
 * 잠금 쿼리는 이미 영속성 컨텍스트에 있는 엔티티를 덮어쓰지 않으므로, 잠근 뒤 대상을 DB에서 다시 읽는다(PR #116 리뷰 R1).
 */
final class ProductPurchaseLock {
    private ProductPurchaseLock() {
    }

    static License lock(LicenseRepository licenses, EntityManager entityManager, String orderNo) {
        License found = licenses.findByOrderNo(orderNo).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return pick(licenses.lockProductPurchases(found.getBuyerSubject(), found.getProductId()), found.getLicenseId(), entityManager);
    }

    static License lockOwned(LicenseRepository licenses, EntityManager entityManager, String buyer, String licenseId) {
        License found = licenses.findByBuyerSubjectAndLicenseId(buyer, licenseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return pick(licenses.lockProductPurchases(found.getBuyerSubject(), found.getProductId()), found.getLicenseId(), entityManager);
    }

    private static License pick(List<License> locked, String licenseId, EntityManager entityManager) {
        for (License purchase : locked) {
            if (purchase.getLicenseId().equals(licenseId)) {
                entityManager.refresh(purchase, LockModeType.PESSIMISTIC_WRITE);
                return purchase;
            }
        }
        throw new BusinessException(ErrorCode.CONFLICT, "잠근 구매 근거가 대상과 다릅니다.");
    }
}

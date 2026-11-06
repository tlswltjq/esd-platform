package com.stove.license.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.LicenseRegistrationRepository;
import com.stove.license.core.domain.LicenseRegistrations;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.PurchaseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchStorage {
    private final LicenseRepository licenses;
    private final LicenseRegistrationRepository registrations;

    public Page<StorageItem> execute(String buyer, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return licenses.findStorage(buyer, PageRequest.of(page, size, Sort.by(Sort.Order.desc("acquiredAt"), Sort.Order.desc("id"))))
                .map(purchase -> new StorageItem(purchase, purchase.getStatus() == PurchaseStatus.STORED
                        && !LicenseRegistrations.activeExists(registrations, purchase)));
    }
}

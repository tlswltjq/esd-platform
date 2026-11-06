package com.stove.license.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.LicenseRegistration;
import com.stove.license.core.domain.LicenseRegistrationRepository;
import com.stove.license.core.domain.LicenseRegistrations;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchLicenses {
    private final LicenseRegistrationRepository registrations;

    /** 라이브러리: 쓸 수 있거나 환불로 정지된 보유권. 끝난 보유권은 뺀다(결정 97). */
    public Page<LicenseRegistration> execute(String buyer, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return registrations.findByBuyerSubjectAndStatusIn(buyer, LicenseRegistrations.OPEN, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("registeredAt"), Sort.Order.desc("id"))));
    }
}

package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.DownloadRestrictionView;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReadDownloadRestriction {
    private final CatalogProductRepository products;
    @Transactional(readOnly = true)
    public DownloadRestrictionView execute(String code) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return DownloadRestrictionView.from(products.findByProductCode(code).filter(p -> code.equals(p.getProductCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }
}

package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.CatalogSalesValues;
import com.stove.catalog.core.domain.CatalogSalesView;
import com.stove.catalog.core.domain.PlatformSaleRestriction;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class ChangePlatformSaleRestriction {
    private final CatalogProductRepository products;
    private final RecordCatalogSaleChange record;
    private final Clock clock;
    @Transactional
    public CatalogSalesView execute(String actor, PlatformSaleRestriction input) {
        CatalogSalesValues.requireActor(actor);
        if (input==null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        CatalogProduct product=products.lockByProductCode(input.productCode()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String before=product.getSellerSaleStatus(); boolean restricted=product.isPlatformRestricted(); long version=product.getCatalogVersion();
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (product.changePlatformRestriction(input.restricted(),input.expectedCatalogVersion(),now))
            record.execute(product,actor,"ADMIN",before,restricted,version,now);
        return CatalogSalesView.from(product);
    }
}

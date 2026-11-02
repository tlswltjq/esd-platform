package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.CatalogDownloadChange;
import com.stove.catalog.core.domain.CatalogDownloadChangeRepository;
import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.DownloadRestrictionChange;
import com.stove.catalog.core.domain.DownloadRestrictionView;
import com.stove.catalog.core.port.BuildDistribution;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChangeDownloadRestriction {
    private final CatalogProductRepository products;
    private final CatalogDownloadChangeRepository changes;
    private final BuildDistribution distribution;
    private final Clock clock;

    @Transactional
    public DownloadRestrictionView execute(String actor, DownloadRestrictionChange input) {
        if (actor == null || actor.isBlank() || actor.length() > 100 || input == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        distribution.requireConfigured();
        CatalogProduct product = products.lockByProductCode(input.productCode())
                .filter(p -> input.productCode().equals(p.getProductCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        boolean before = product.isDownloadRestricted();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (product.changeDownloadRestriction(input.restricted(), input.expectedRestrictionVersion(), now))
            changes.saveAndFlush(CatalogDownloadChange.record(product, actor, before, input.reason(), now));
        return DownloadRestrictionView.from(product);
    }
}

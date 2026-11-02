package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.CatalogListingChange;
import com.stove.catalog.core.domain.CatalogListingChangeRepository;
import com.stove.catalog.core.domain.CatalogListingView;
import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.ListingChange;
import com.stove.catalog.core.domain.ListingPreference;
import com.stove.catalog.core.domain.ListingValues;
import com.stove.catalog.core.domain.PlatformListingChange;
import com.stove.common.kafka.contract.payload.CatalogProductPublishedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
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
public class ChangeCatalogListing {
    private final CatalogProductRepository products;
    private final CatalogListingChangeRepository changes;
    private final ReadPublishedProducts published;
    private final OutboxRecorder outbox;
    private final Clock clock;

    @Transactional
    public CatalogListingView seller(String actor, ListingChange input) {
        ListingValues.requireActor(actor);
        CatalogProduct product = require(input.productCode());
        if (!actor.equals(product.getOwnerSubject())) throw new BusinessException(ErrorCode.NOT_FOUND);
        ListingPreference before = product.getListingPreference();
        boolean hidden = product.isPlatformListingHidden();
        long version = product.getCatalogVersion();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (product.changeListingPreference(input.preference(), input.expectedCatalogVersion(), now))
            record(product, actor, "CREATOR", before, hidden, version, now);
        return CatalogListingView.from(product);
    }
    @Transactional
    public CatalogListingView platform(String actor, PlatformListingChange input) {
        ListingValues.requireActor(actor);
        CatalogProduct product = require(input.productCode());
        ListingPreference before = product.getListingPreference();
        boolean hidden = product.isPlatformListingHidden();
        long version = product.getCatalogVersion();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (product.changePlatformListingHidden(input.hidden(), input.expectedCatalogVersion(), now))
            record(product, actor, "ADMIN", before, hidden, version, now);
        return CatalogListingView.from(product);
    }
    private CatalogProduct require(String code) {
        return products.lockByProductCode(code).filter(p -> code.equals(p.getProductCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
    private void record(CatalogProduct product, String actor, String authority, ListingPreference before,
            boolean hidden, long version, Instant now) {
        changes.saveAndFlush(CatalogListingChange.record(product, actor, authority, before, hidden, version, now));
        outbox.record("CatalogProduct", product.getProductCode(), CatalogProductPublishedEvent.of(published.internalDetail(product.getProductCode()).publicSnapshot()));
    }
}

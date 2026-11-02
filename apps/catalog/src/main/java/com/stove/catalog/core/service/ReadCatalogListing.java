package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.CatalogListingView;
import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.ListingValues;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadCatalogListing {
    private final CatalogProductRepository products;
    public CatalogListingView seller(String actor, String code) {
        ListingValues.requireActor(actor);
        CatalogProduct product = require(code);
        if (!actor.equals(product.getOwnerSubject())) throw new BusinessException(ErrorCode.NOT_FOUND);
        return CatalogListingView.from(product);
    }
    public CatalogListingView platform(String code) { return CatalogListingView.from(require(code)); }
    private CatalogProduct require(String code) {
        ListingValues.requireProductCode(code);
        return products.findByProductCode(code).filter(p -> code.equals(p.getProductCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}

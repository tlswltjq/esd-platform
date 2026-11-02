package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.CatalogSalesValues;
import com.stove.catalog.core.domain.CatalogSalesView;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ReadCatalogSales {
    private final CatalogProductRepository products;
    public CatalogSalesView seller(String actor,String code) {
        CatalogSalesValues.requireActor(actor);
        CatalogProduct product=require(code);
        if (!actor.equals(product.getOwnerSubject())) throw new BusinessException(ErrorCode.NOT_FOUND);
        return CatalogSalesView.from(product);
    }
    public CatalogSalesView platform(String code) { return CatalogSalesView.from(require(code)); }
    private CatalogProduct require(String code) {
        CatalogSalesValues.requireCode(code);
        return products.findByProductCode(code).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}

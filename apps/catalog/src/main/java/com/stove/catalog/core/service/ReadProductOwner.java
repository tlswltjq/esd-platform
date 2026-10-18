package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.ProductOwner;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class ReadProductOwner {
    private final CatalogProductRepository products;
    public ReadProductOwner(CatalogProductRepository products) { this.products=products; }

    public ProductOwner execute(String code) {
        if (code==null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        CatalogProduct product=products.findByProductCode(code).filter(p -> code.equals(p.getProductCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return new ProductOwner(product.getId(),product.getProductCode(),product.getOwnerSubject());
    }
}

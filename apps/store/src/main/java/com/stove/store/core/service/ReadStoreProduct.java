package com.stove.store.core.service;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.*;
import com.stove.store.core.domain.StoreProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReadStoreProduct {
    private final StoreProductRepository products;
    private final SynchronizeCatalogProducts synchronization;
    @Transactional(readOnly = true)
    public CatalogProductSnapshot execute(String code) {
        synchronization.requireReady();
        return products.findByProductCode(code).filter(p -> p.getProductCode().equals(code))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).snapshot();
    }
}

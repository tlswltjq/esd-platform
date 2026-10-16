package com.stove.store.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.store.core.domain.StoreErrorCode;
import com.stove.store.core.port.CatalogProducts;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SynchronizeCatalogProducts {
    private final CatalogProducts catalog;
    private final ApplyCatalogProduct applyCatalogProduct;
    private volatile boolean ready;

    public void execute() {
        ready = false;
        for (int page = 0; ; page++) {
            List<String> codes = catalog.codes(page);
            if (codes.isEmpty()) break;
            for (String code : codes) applyCatalogProduct.execute(catalog.detail(code));
        }
        ready = true;
    }

    public void requireReady() {
        if (!ready) throw new BusinessException(StoreErrorCode.NOT_READY);
    }
}

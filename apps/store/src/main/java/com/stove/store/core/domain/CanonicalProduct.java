package com.stove.store.core.domain;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

/** Constructed after directly reading the Catalog port; never used by the Kafka listener. */
public record CanonicalProduct(String requestedCode, CatalogProductSnapshot snapshot) {
    public CanonicalProduct {
        StoreProductValues.requireCode(requestedCode);
        StoreProductValues.requireSnapshot(snapshot);
        if (!requestedCode.equals(snapshot.productCode()))
            throw new BusinessException(ErrorCode.CONFLICT, "Catalog 정본의 상품 코드가 다릅니다.");
    }
}

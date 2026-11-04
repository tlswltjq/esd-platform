package com.stove.store.core.domain;

import com.stove.common.kafka.contract.genre.GenreCatalog;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public final class StoreProductValues {
    private StoreProductValues() {}

    public static void requireCode(String code) {
        if (!text(code, 50) || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "상품 코드가 올바르지 않습니다.");
    }

    public static void requireSnapshot(CatalogProductSnapshot snapshot) {
        if (snapshot == null || snapshot.productId() == null || snapshot.productId() <= 0 || snapshot.contentVersion() <= 0
                || !text(snapshot.productCode(), 50) || !snapshot.productCode().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")
                || !text(snapshot.title(), 200) || !text(snapshot.shortDescription(), 500)
                || !text(snapshot.thumbnailUrl(), 1000) || !text(snapshot.content(), 100000)
                || (snapshot.videoUrl() != null && snapshot.videoUrl().length() > 1000)
                || !text(snapshot.buildVersion(), 100) || snapshot.price() < 0 || !"KRW".equals(snapshot.currency())
                || snapshot.publishedAt() == null || snapshot.updatedAt() == null
                || (!"ON_SALE".equals(snapshot.saleStatus()) && !"STOPPED".equals(snapshot.saleStatus()) && !"BLOCKED".equals(snapshot.saleStatus()))
                || snapshot.catalogVersion() < snapshot.contentVersion() || snapshot.listed() == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개 상품 데이터가 올바르지 않습니다.");
        try { GenreCatalog.standard().published(snapshot.genres()); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개 상품 장르가 올바르지 않습니다."); }
    }

    private static boolean text(String value, int limit) { return value != null && !value.isBlank() && value.length() <= limit; }
}

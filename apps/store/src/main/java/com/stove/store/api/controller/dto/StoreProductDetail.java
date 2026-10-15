package com.stove.store.api.controller.dto;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import java.time.Instant;
public record StoreProductDetail(Long productId, String productCode, String title, String shortDescription,
        String thumbnailUrl, long price, String currency, Instant publishedAt, String content, String videoUrl,
        long contentVersion, String buildVersion, Instant updatedAt) {
    public static StoreProductDetail from(CatalogProductSnapshot p) {
        return new StoreProductDetail(p.productId(), p.productCode(), p.title(), p.shortDescription(),
                p.thumbnailUrl(), p.price(), p.currency(), p.publishedAt(), p.content(), p.videoUrl(),
                p.contentVersion(), p.buildVersion(), p.updatedAt());
    }
}

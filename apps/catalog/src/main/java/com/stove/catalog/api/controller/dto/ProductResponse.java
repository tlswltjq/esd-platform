package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.ProductStatus;
import com.stove.catalog.core.domain.ProductView;
import com.stove.common.event.payload.PromotionWindow;
import com.stove.common.event.payload.StorefrontSnapshot;
import java.util.List;

public record ProductResponse(
        Long productId,
        String productCode,
        Long gameId,
        Long releaseId,
        Long buildId,
        Long metadataRevision,
        String name,
        Long sellerId,
        long price,
        String currency,
        ProductStatus status,
        String ratingCode,
        String productKind,
        String parentProductCode,
        String editionName,
        List<String> bundleProductCodes,
        boolean visible,
        boolean purchasable,
        StorefrontSnapshot storefront,
        long listPrice,
        long discountAmount,
        Long promotionId,
        String discountBearer
) {
    public static ProductResponse from(ProductView product) {
        return from(product, null);
    }

    public static ProductResponse from(ProductView product, PromotionWindow promotion) {
        long discount = promotion == null ? 0 : promotion.discountPerUnit();
        return new ProductResponse(
                product.productId(),
                product.productCode(),
                product.gameId(),
                product.releaseId(),
                product.buildId(),
                product.metadataRevision(),
                product.name(),
                product.sellerId(),
                product.price() - discount,
                product.currency(),
                product.status(),
                product.ratingCode(),
                product.productKind(),
                product.parentProductCode(),
                product.editionName(),
                product.bundleProductCodes(), product.visible(), product.purchasable(), product.storefront(),
                product.price(), discount, promotion == null ? null : promotion.id(),
                promotion == null ? null : promotion.bearer());
    }
}

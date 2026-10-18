package com.stove.download.core.domain;

public record OwnedProduct(long productId, String productCode) {
    public OwnedProduct {
        if (productId <= 0 || !DownloadValues.productCode(productCode))
            throw new IllegalArgumentException("Invalid owned product");
    }
}

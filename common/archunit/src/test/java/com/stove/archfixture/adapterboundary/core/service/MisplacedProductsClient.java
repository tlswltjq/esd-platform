package com.stove.archfixture.adapterboundary.core.service;

import com.stove.archfixture.adapterboundary.core.port.RemoteProducts;

public final class MisplacedProductsClient implements RemoteProducts {
    @Override
    public String title() {
        return "product";
    }
}

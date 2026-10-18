package com.stove.archfixture.adapterboundary.config;

import com.stove.archfixture.adapterboundary.adapter.RemoteProductsClient;
import com.stove.archfixture.adapterboundary.core.port.RemoteProducts;

public final class ProductsConfiguration {
    public RemoteProducts remoteProducts() {
        return new RemoteProductsClient();
    }
}

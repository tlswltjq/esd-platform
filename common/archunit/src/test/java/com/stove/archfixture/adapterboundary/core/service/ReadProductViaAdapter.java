package com.stove.archfixture.adapterboundary.core.service;

import com.stove.archfixture.adapterboundary.adapter.RemoteProductsClient;

public final class ReadProductViaAdapter {
    private final RemoteProductsClient products;

    public ReadProductViaAdapter(RemoteProductsClient products) {
        this.products = products;
    }

    public String title() {
        return products.title();
    }
}

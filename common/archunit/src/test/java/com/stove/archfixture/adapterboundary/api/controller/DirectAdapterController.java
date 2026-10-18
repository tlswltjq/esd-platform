package com.stove.archfixture.adapterboundary.api.controller;

import com.stove.archfixture.adapterboundary.adapter.RemoteProductsClient;

public final class DirectAdapterController {
    private final RemoteProductsClient products;

    public DirectAdapterController(RemoteProductsClient products) {
        this.products = products;
    }

    public String title() {
        return products.title();
    }
}

package com.stove.archfixture.adapterboundary.core.service;

import com.stove.archfixture.adapterboundary.core.port.RemoteProducts;

public final class ReadProduct {
    private final RemoteProducts products;

    public ReadProduct(RemoteProducts products) {
        this.products = products;
    }

    public String title() {
        return products.title();
    }
}

package com.stove.archfixture.adapterboundary.adapter;

import com.stove.archfixture.adapterboundary.core.port.RemoteProducts;

public final class RemoteProductsClient implements RemoteProducts {
    @Override
    public String title() {
        return "product";
    }
}

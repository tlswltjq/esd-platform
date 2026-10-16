package com.stove.order.core.port;

import com.stove.order.core.domain.PublishedProduct;

public interface CatalogProducts { PublishedProduct detail(String productCode); }

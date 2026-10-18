package com.stove.download.core.port;

import com.stove.download.core.domain.OwnedProduct;

public interface OwnedProducts {
    OwnedProduct require(String productCode, String accessToken);
}

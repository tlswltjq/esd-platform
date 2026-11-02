package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

/** Policy B applies to new orders; existing admission uses its original policy. */
public final class ProductPublicAccess {
    private ProductPublicAccess() {}
    public static void requireNewOrder(boolean publicDetailAllowed) {
        if (!publicDetailAllowed) throw new BusinessException(ErrorCode.NOT_FOUND);
    }
}

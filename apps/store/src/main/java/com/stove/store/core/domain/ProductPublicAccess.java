package com.stove.store.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

/** Public-detail policy B. Internal business reads never use this policy. */
public final class ProductPublicAccess {
    private ProductPublicAccess() {}
    public static boolean allowsDetail(boolean listed) { return listed; }
    public static void requireDetail(boolean listed) {
        if (!allowsDetail(listed)) throw new BusinessException(ErrorCode.NOT_FOUND);
    }
}

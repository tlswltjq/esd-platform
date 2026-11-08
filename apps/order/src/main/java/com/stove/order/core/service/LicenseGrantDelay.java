package com.stove.order.core.service;

import java.time.Duration;

/** 결정 125 경계 값의 공통 검증. 게이지와 고객 안내가 같은 규칙으로 받는다. */
final class LicenseGrantDelay {
    private LicenseGrantDelay() { }

    static Duration require(Duration threshold) {
        if (threshold == null || threshold.isZero() || threshold.isNegative())
            throw new IllegalArgumentException("A positive license grant delay boundary is required");
        return threshold;
    }
}

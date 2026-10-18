package com.stove.license.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

import java.time.Instant;

public final class LicenseValues {
    private LicenseValues() {
    }

    static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    public static boolean productCode(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}");
    }

    static boolean microsecond(Instant value) {
        return value != null && value.getNano() % 1000 == 0;
    }

    static void require(boolean valid, String message) {
        if (!valid) throw new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    static void consistent(boolean valid, String message) {
        if (!valid) throw new BusinessException(ErrorCode.CONFLICT, message);
    }
}

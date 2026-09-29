package com.stove.review.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import java.util.Arrays;

public enum ReviewReasonCode {
    METADATA,
    BUILD,
    METADATA_INCOMPLETE,
    ASSET_QUALITY,
    BUILD_FAILURE,
    POLICY_VIOLATION,
    LEGAL_DOCUMENT,
    SDK_REQUIREMENT,
    COMMERCIAL_TERMS,
    EXTERNAL_DEPENDENCY,
    CREATOR_REQUEST,
    SLA_EXPIRED,
    OTHER;

    public static void requireValid(String value) {
        boolean valid = value != null && Arrays.stream(values()).anyMatch(item -> item.name().equals(value));
        if (!valid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "표준 심사 사유 코드가 필요합니다.");
        }
    }
}

package com.stove.review.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import java.util.Arrays;

/**
 * 심사 판단과 수정 요청에 사용할 표준 사유 코드.
 */
public enum ReviewReasonCode {
    /** 상점 소개 자료와 관련된 심사 사유. */
    METADATA,
    /** 빌드 파일과 관련된 심사 사유. */
    BUILD,
    /** 상점 소개 자료의 필수 내용이 부족함. */
    METADATA_INCOMPLETE,
    /** 이미지·영상 자료의 품질 보완이 필요함. */
    ASSET_QUALITY,
    /** 빌드 실행 또는 검증에 실패함. */
    BUILD_FAILURE,
    /** 플랫폼 정책을 위반함. */
    POLICY_VIOLATION,
    /** 법적 증빙 또는 계약 자료의 보완이 필요함. */
    LEGAL_DOCUMENT,
    /** SDK 연동 요구 사항을 충족하지 못함. */
    SDK_REQUIREMENT,
    /** 판매 조건의 확인 또는 보완이 필요함. */
    COMMERCIAL_TERMS,
    /** 외부 기관이나 시스템의 처리를 기다림. */
    EXTERNAL_DEPENDENCY,
    /** 창작자의 요청에 따른 처리. */
    CREATOR_REQUEST,
    /** 정해진 심사 처리 기한을 초과함. */
    SLA_EXPIRED,
    /** 다른 표준 코드에 해당하지 않는 사유. */
    OTHER;

    public static void requireValid(String value) {
        boolean valid = value != null && Arrays.stream(values()).anyMatch(item -> item.name().equals(value));
        if (!valid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "표준 심사 사유 코드가 필요합니다.");
        }
    }
}

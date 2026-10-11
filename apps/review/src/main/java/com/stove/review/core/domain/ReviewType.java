package com.stove.review.core.domain;

/**
 * 제출물에서 독립적으로 심사하는 업무 영역.
 */
public enum ReviewType {
    /** 이용 등급과 등급분류 증명을 심사. */
    RATING,
    /** 상점에 공개할 소개 자료를 심사. */
    STORE_PAGE,
    /** 빌드 품질과 실행 가능 여부를 심사. */
    BUILD_QA,
    /** 법적 권리와 계약 자료를 심사. */
    LEGAL,
    /** 플랫폼 SDK 연동 요구 사항 준수를 심사. */
    SDK_COMPLIANCE,
    /** 가격 등 판매 조건을 심사. */
    COMMERCIAL
}

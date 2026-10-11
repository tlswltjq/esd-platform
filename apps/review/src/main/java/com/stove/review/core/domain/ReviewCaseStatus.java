package com.stove.review.core.domain;

/**
 * 제출물의 유형별 심사 사건 진행 상태.
 */
public enum ReviewCaseStatus {
    /** 심사 요청을 접수한 상태. */
    REQUESTED,
    /** 외부 등급분류 기관에 신청을 접수한 상태. */
    EXTERNAL_SUBMITTED,
    /** 심사 또는 승인 처리가 완료된 상태. */
    APPROVED,
    /** 창작자에게 자료 수정을 요청한 상태. */
    CHANGES_REQUESTED,
    /** 해결이 필요한 사유로 심사 진행이 보류된 상태. */
    BLOCKED,
    /** 예약 또는 요청이 취소된 상태. */
    CANCELLED,
    /** 유효 기간 또는 처리 기한이 지난 상태. */
    EXPIRED
}

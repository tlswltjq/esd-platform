package com.stove.studio.core.domain.submission;

/**
 * 출시 관문 하나의 심사 대기·승인·수정 요청 상태.
 */
public enum SubmissionGateStatus {
    /** 해당 유형의 심사 결과를 기다리는 상태. */
    PENDING,
    /** 심사 또는 승인 처리가 완료된 상태. */
    APPROVED,
    /** 창작자에게 자료 수정을 요청한 상태. */
    CHANGES_REQUESTED
}
